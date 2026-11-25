"""Inspect a copied phone capture without modifying it or treating it as training-ready."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image


def validate(folder):
    folder = Path(folder).resolve()
    manifest = json.loads((folder / 'capture.json').read_text(encoding='utf-8'))
    errors, positions, times, dimensions = [], [], [], set()
    image_times, offsets = [], []
    frames = sorted(folder.glob('frame-*.json'))
    points = 0
    cloud_statuses, rejected_points = {}, 0
    for path in frames:
        try:
            frame = json.loads(path.read_text(encoding='utf-8'))
            image = (folder / frame['image']).resolve()
            if image.parent != folder:
                raise ValueError('Image must stay inside capture folder')
            if hashlib.sha256(image.read_bytes()).hexdigest() != frame['image_sha256']:
                raise ValueError('Image checksum mismatch')
            with Image.open(image) as jpeg:
                jpeg.load()
                if jpeg.size != (frame['width'], frame['height']):
                    raise ValueError('JPEG and camera dimensions differ')
            dimensions.add((frame['width'], frame['height']))
            t = frame['frame_timestamp_ns']
            camera_time = frame.get('android_camera_timestamp_ns', t)
            image_time = frame['image_timestamp_ns']
            if manifest.get('schema_version', 1) >= 3:
                if frame.get('pairing_method') != 'ARCore.Frame.acquireCameraImage/current-update':
                    raise ValueError('Missing SDK same-update image pairing contract')
                if frame.get('image_minus_android_camera_ns') != image_time-camera_time or frame.get('frame_minus_image_ns') != t-image_time:
                    raise ValueError('Timestamp diagnostic mismatch')
            elif camera_time != image_time:
                raise ValueError('Legacy camera timestamp mismatch')
            if t <= 0 or image_time <= 0 or (times and t <= times[-1]) or (image_times and image_time <= image_times[-1]):
                raise ValueError('Repeated or invalid frame/image timestamps')
            image_times.append(image_time); offsets.append(image_time-camera_time)
            if frame['tracking_state'] != 'TRACKING':
                raise ValueError('Non-tracking camera pose committed')
            gl = np.array(frame['camera_to_world_gl_column_major']).reshape(4, 4, order='F')
            cv = np.array(frame['camera_to_world_cv_column_major']).reshape(4, 4, order='F')
            if not np.isfinite(gl).all() or not np.isfinite(cv).all():
                raise ValueError('Non-finite camera pose')
            np.testing.assert_allclose(cv, gl @ np.diag([1, -1, -1, 1]), atol=1e-5)
            np.testing.assert_allclose(gl[3], [0, 0, 0, 1], atol=1e-5)
            np.testing.assert_allclose(gl[:3, :3].T @ gl[:3, :3], np.eye(3), atol=1e-4)
            if abs(np.linalg.det(gl[:3, :3])-1) > 1e-4:
                raise ValueError('Camera rotation determinant differs from one')
            intrinsics = np.array(frame['focal_length_px'] + frame['principal_point_px'])
            if not np.isfinite(intrinsics).all() or np.any(intrinsics[:2] <= 0):
                raise ValueError('Invalid image intrinsics')
            cloud = np.array(frame.get('point_cloud_world_xyzc', []), dtype=float).reshape(-1, 4)
            if not np.isfinite(cloud).all():
                raise ValueError('Non-finite sparse point cloud')
            if len(cloud) and (np.any(cloud[:, 3] < 0) or np.any(cloud[:, 3] > 1)):
                raise ValueError('Invalid sparse point confidence')
            status = frame.get('point_cloud_status', 'legacy')
            cloud_statuses[status] = cloud_statuses.get(status, 0) + 1
            rejected_points += frame.get('point_cloud_invalid_points', 0)
            points += len(cloud)
            positions.append(gl[:3, 3]); times.append(t)
        except Exception as error:
            errors.append(f'{path.name}: {error}')
    if not frames:
        errors.append('No committed frames')
    if manifest.get('state') != 'recording' and manifest.get('frames') != len(frames):
        errors.append('Final manifest count differs from committed frame records')
    extent = np.ptp(positions, axis=0).tolist() if positions else [0, 0, 0]
    return {
        'capture_id': manifest['id'], 'state': manifest['state'],
        'committed_frames': len(frames), 'valid_frames': len(positions),
        'image_dimensions': sorted(dimensions), 'camera_position_extent_m': extent,
        'sparse_point_observations': points, 'data_contract_passed': not errors,
        'point_cloud_status_counts': cloud_statuses, 'filtered_invalid_point_observations': rejected_points,
        'image_minus_android_camera_ns_range': [min(offsets), max(offsets)] if offsets else None,
        'errors': errors, 'training_ready': False,
        'limitations': 'Checks file integrity and internal coordinate consistency only. '
                      'Does not prove pose accuracy, complete furniture coverage, sharpness, or successful reconstruction.'
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = validate(args.capture)
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        args.report.write_text(text, encoding='utf-8')
    print(text)
    raise SystemExit(0 if report['data_contract_passed'] else 1)
