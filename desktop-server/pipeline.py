"""Phone capture -> known-pose COLMAP refinement -> desktop Brush Gaussian training."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time

import numpy as np
from scipy.spatial.transform import Rotation

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from inspect_result import read_gaussians
from reconstruct import gaussian_info, prepare_brush_images


class Cancelled(Exception):
    pass


class BudgetReached(Exception):
    pass


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            value.update(block)
    return value.hexdigest()


def execute(command, log, deadline, cancelled):
    env = os.environ.copy()
    env['QT_QPA_PLATFORM'] = 'offscreen'
    with Path(log).open('wb') as output:
        process = subprocess.Popen(list(map(str, command)), stdout=output, stderr=subprocess.STDOUT,
                                   env=env, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            while process.poll() is None:
                if cancelled.is_set():
                    raise Cancelled()
                if time.monotonic() >= deadline:
                    raise BudgetReached()
                time.sleep(.2)
            if process.returncode:
                raise RuntimeError(f'{Path(command[0]).name} failed ({process.returncode}); see {log}')
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(5)


def validate_capture(source):
    spec = importlib.util.spec_from_file_location('capture_validation', ROOT/'android-app/validate_capture.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    report = module.validate(source)
    if report['state'] != 'completed' or not report['data_contract_passed']:
        raise ValueError('扫描照片或相机记录不完整，请重新保存后上传')
    if not 20 <= report['valid_frames'] <= 300:
        raise ValueError('需要20至300张完整扫描画面')
    return report


def read_poses(path):
    lines = path.read_text(encoding='utf-8').splitlines()
    result = {}
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line or line.startswith('#'):
            continue
        v = line.split()
        result[v[9]] = (int(v[0]), np.array(v[1:5], float), np.array(v[5:8], float), int(v[8]))
        i += 1
    return result


def rotation(q):
    return Rotation.from_quat(q[[1, 2, 3, 0]]).as_matrix()


def center(pose):
    return -rotation(pose[1]).T @ pose[2]


def process_capture(folder, update, cancelled, budget_seconds=600, steps=7000, resolution=1280):
    started = time.monotonic()
    # Reserve time to validate and publish a final file or the latest checkpoint.
    deadline = started + budget_seconds - min(20, budget_seconds*.1)
    source = folder/'capture'
    update(phase='validating', message='检查扫描照片与相机记录')
    report = validate_capture(source)
    (folder/'capture-validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    metadata = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(source.glob('frame-*.json'))]
    images = folder/'dataset/images'
    images.mkdir(parents=True)
    originals = {}
    for frame in metadata:
        name = frame['image']
        shutil.copyfile(source/name, images/name)
        pose = np.array(frame['camera_to_world_cv_column_major']).reshape(4, 4, order='F')
        world_to_camera = np.linalg.inv(pose)
        q = Rotation.from_matrix(world_to_camera[:3, :3]).as_quat()[[3, 0, 1, 2]]
        originals[name] = (0, q, world_to_camera[:3, 3], 0)
    colmap = ROOT/'tools/colmap/bin/colmap.exe'
    brush = ROOT/'tools/brush/brush_app.exe'
    if not colmap.is_file() or not brush.is_file():
        raise ValueError('电脑重建工具不完整，请先完成工具准备')
    db = folder/'features.db'
    def run(stage, *command):
        execute(command, folder/(stage+'.log'), deadline, cancelled)
    update(phase='matching', message='电脑匹配多角度照片')
    run('features', colmap, 'feature_extractor', '--database_path', db, '--image_path', images,
        '--ImageReader.single_camera_per_image', '1', '--ImageReader.camera_model', 'PINHOLE',
        '--FeatureExtraction.use_gpu', '0', '--FeatureExtraction.num_threads', '8',
        '--FeatureExtraction.max_image_size', '1600', '--SiftExtraction.peak_threshold', '0.002',
        '--SiftExtraction.estimate_affine_shape', '1', '--SiftExtraction.domain_size_pooling', '1')
    # Match database camera/image IDs exactly. ARCore provides per-frame intrinsics;
    # do not hard-code the first chair's focal length or assume shared intrinsics.
    by_name = {f['image']: f for f in metadata}
    known = folder/'known'
    known.mkdir()
    camera_lines, pose_lines = [], []
    with sqlite3.connect(db) as connection:
        database_images = list(connection.execute('SELECT image_id,name,camera_id FROM images'))
        for image_id, name, camera_id in database_images:
            frame = by_name[name]
            params = np.array([*frame['focal_length_px'], *frame['principal_point_px']], dtype=np.float64)
            connection.execute('UPDATE cameras SET model=1,width=?,height=?,params=?,prior_focal_length=1 WHERE camera_id=?',
                               (frame['width'], frame['height'], params.tobytes(), camera_id))
            camera_lines.append(f"{camera_id} PINHOLE {frame['width']} {frame['height']} "+' '.join(map(str, params))+'\n')
            _, q, t, _ = originals[name]
            pose_lines.append(' '.join(map(str, [image_id, *q, *t, camera_id, name]))+'\n\n')
            originals[name] = image_id, q, t, camera_id
    (known/'cameras.txt').write_text(''.join(camera_lines), encoding='utf-8')
    (known/'images.txt').write_text(''.join(pose_lines), encoding='utf-8')
    (known/'points3D.txt').write_text('', encoding='utf-8')
    names = sorted(by_name)
    pairs = folder/'pairs.txt'
    # Neighbouring views plus orbit closure, bounded for longer 300-frame scans.
    pairs.write_text(''.join(f'{names[i]} {names[j]}\n' for i in range(len(names)) for j in range(i+1, len(names))
                             if min(j-i, len(names)-(j-i)) <= 20), encoding='utf-8')
    run('matching', colmap, 'matches_importer', '--database_path', db, '--match_list_path', pairs,
        '--match_type', 'pairs', '--FeatureMatching.use_gpu', '0', '--FeatureMatching.num_threads', '8',
        '--FeatureMatching.guided_matching', '1')
    update(phase='refining', message='电脑优化相机定位与三维几何')
    tri, ba, text = [folder/p for p in ('triangulated', 'bundle-adjusted', 'refined-text')]
    for path in (tri, ba, text):
        path.mkdir()
    run('triangulate', colmap, 'point_triangulator', '--database_path', db, '--image_path', images,
        '--input_path', known, '--output_path', tri, '--refine_intrinsics', '0',
        '--Mapper.ba_refine_focal_length', '0', '--Mapper.ba_refine_extra_params', '0')
    run('bundle-adjust', colmap, 'bundle_adjuster', '--input_path', tri, '--output_path', ba,
        '--BundleAdjustment.refine_focal_length', '0', '--BundleAdjustment.refine_principal_point', '0',
        '--BundleAdjustment.refine_extra_params', '0', '--BundleAdjustmentCeres.max_num_iterations', '80')
    run('export', colmap, 'model_converter', '--input_path', ba, '--output_path', text, '--output_type', 'TXT')
    refined = read_poses(text/'images.txt')
    shared = sorted(set(refined) & set(originals))
    if len(shared) < max(20, len(originals)*.8):
        raise ValueError('有效重叠视角不足，电脑未能稳定优化这次扫描')
    x, y = np.array([center(refined[n]) for n in shared]), np.array([center(originals[n]) for n in shared])
    mx, my = x.mean(0), y.mean(0)
    xc, yc = x-mx, y-my
    u, d, vt = np.linalg.svd(yc.T@xc/len(x))
    sign = np.ones(3)
    sign[-1] = np.linalg.det(u@vt)
    r = u@np.diag(sign)@vt
    s = (d*sign).sum()/np.mean(np.sum(xc*xc, axis=1))
    t = my-s*r@mx
    if not np.isfinite(s) or not .2 < s < 5:
        raise ValueError('相机优化比例异常，未发布不可靠模型')
    target = folder/'dataset/sparse/0'
    target.mkdir(parents=True)
    shutil.copyfile(text/'cameras.txt', target/'cameras.txt')
    lines, corrections = [], []
    for name in shared:
        image_id, q, _, camera_id = refined[name]
        c = s*r@center(refined[name])+t
        rr = rotation(q)@r.T
        q = Rotation.from_matrix(rr).as_quat()[[3, 0, 1, 2]]
        lines.append(' '.join(map(str, [image_id, *q, *(-rr@c), camera_id, name]))+'\n\n')
        corrections.append(float(np.linalg.norm(c-center(originals[name]))))
    if np.median(corrections) > .3:
        raise ValueError('相机定位漂移较大，请增加重叠视角后重试')
    (target/'images.txt').write_text(''.join(lines), encoding='utf-8')
    points = []
    for line in (text/'points3D.txt').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        v = line.split()
        xyz = s*r@np.array(v[1:4], float)+t
        points.append(' '.join([v[0], *map(str, xyz), *v[4:8]])+'\n')
    if len(points) < 100:
        raise ValueError('可重建空间点不足，请拍摄更多清晰、有纹理的重叠视角')
    (target/'points3D.txt').write_text(''.join(points), encoding='utf-8')
    (folder/'geometry-report.json').write_text(json.dumps({'images': len(shared), 'triangulated_points': len(points),
        'alignment_scale': float(s), 'alignment_rotation': r.tolist(), 'alignment_translation': t.tolist(),
        'median_pose_correction_m': float(np.median(corrections)), 'coordinate_frame': 'Aligned to original ARCore world meters'}), encoding='utf-8')
    update(phase='training', message='电脑显卡训练高斯模型', requested_steps=steps)
    trained = folder/'trained'
    trained.mkdir()
    prepare_brush_images(folder/'dataset')
    limited = False
    try:
        run('training', brush, folder/'dataset', '--total-steps', steps, '--max-resolution', resolution,
            '--sh-degree', '2', '--max-splats', '250000', '--growth-stop-iter', min(4000, steps*2//3),
            '--growth-grad-threshold', '0.00004', '--refine-every', '200', '--export-every', '1000',
            '--eval-every', steps, '--eval-split-every', '8', '--eval-save-to-disk',
            '--export-path', trained, '--export-name', 'model_{iter}.ply', '--seed', '42')
    except BudgetReached:
        limited = True
    if cancelled.is_set():
        raise Cancelled()
    candidates = sorted(trained.glob('model_*.ply'), key=lambda p: int(p.stem.split('_')[1]), reverse=True)
    model = None
    for path in candidates:
        try:
            info = gaussian_info(path)
            records = read_gaussians(path)
            if not all(np.isfinite(records[k]).all() for k in records.dtype.names):
                continue
            model = path
            break
        except Exception:
            continue
    if model is None:
        raise ValueError('时间预算内未生成有效模型；扫描照片已保留，可重试')
    completed = int(model.stem.split('_')[1])
    if not limited and completed != steps:
        raise ValueError('训练未完整结束，未发布不完整结果')
    update(phase='packaging', message='检查模型并准备回传手机')
    shutil.copyfile(model, folder/'model.ply')
    return {'completed_steps': completed, 'requested_steps': steps, 'gaussians': info['gaussian_count'],
            'model_sha256': sha(folder/'model.ply'), 'model_bytes': (folder/'model.ply').stat().st_size,
            'elapsed_seconds': round(time.monotonic()-started, 3), 'stopped_for_time_budget': limited,
            'quality_state': 'time_limited' if limited else 'all_steps_completed',
            'backend': 'COLMAP known-pose triangulation and BA / Brush / computer GPU',
            'scope': 'Desktop image matching, triangulated geometry and pose refinement aligned to ARCore metric coordinates; Gaussian training on computer'}
