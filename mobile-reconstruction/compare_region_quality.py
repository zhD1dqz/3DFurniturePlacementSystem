"""Compare two runs at identical resolution, using held-out images and a fixed projected crop.

The crop projection includes background within the 3D box; it is not a semantic mask.
"""
import argparse
import itertools
import json
from pathlib import Path
import cv2
import numpy as np


def score(source, render, mask):
    mse = np.mean((source[mask] - render[mask]) ** 2)
    a = cv2.cvtColor(source.astype('uint8'), cv2.COLOR_BGR2GRAY).astype(float)
    b = cv2.cvtColor(render.astype('uint8'), cv2.COLOR_BGR2GRAY).astype(float)
    blur = lambda x: cv2.GaussianBlur(x, (11, 11), 1.5)
    ma, mb = blur(a), blur(b)
    va, vb, cov = blur(a*a)-ma*ma, blur(b*b)-mb*mb, blur(a*b)-ma*mb
    ssim = (2*ma*mb+6.5025)*(2*cov+58.5225)/((ma*ma+mb*mb+6.5025)*(va+vb+58.5225))
    valid = mask.copy(); valid[:5] = valid[-5:] = False; valid[:, :5] = valid[:, -5:] = False
    if valid.sum() < 100:
        raise ValueError('Projected region is too small')
    return {'psnr_db': float(10*np.log10(255**2/max(mse, 1e-10))), 'ssim': float(ssim[valid].mean())}


def compare(capture, baseline, trial, crop):
    crop = json.loads(Path(crop).read_text(encoding='utf-8'))['crop']
    local = np.array(list(itertools.product([-1, 1], repeat=3))) * np.array(crop['size'])/2
    c, s = np.cos(crop['yaw_rad']), np.sin(crop['yaw_rad'])
    world = local @ np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]]).T + crop['center']
    common = sorted(set(p.name for p in Path(baseline).glob('frame-*.png')) & set(p.name for p in Path(trial).glob('frame-*.png')))
    if not common:
        raise ValueError('No common evaluation views')
    rows = []
    for name in common:
        stem = Path(name).stem
        source = cv2.imread(str(Path(capture)/(stem+'.jpg')))
        a = cv2.imread(str(Path(baseline)/name)); b = cv2.imread(str(Path(trial)/name))
        # Never compare a larger image directly against a smaller baseline.
        width = min(source.shape[1], a.shape[1], b.shape[1])
        height = round(source.shape[0]*width/source.shape[1])
        source, a, b = [cv2.resize(x, (width, height), interpolation=cv2.INTER_AREA).astype(float) for x in (source, a, b)]
        meta = json.loads((Path(capture)/(stem+'.json')).read_text(encoding='utf-8'))
        pose = np.array(meta['camera_to_world_cv_column_major']).reshape(4, 4, order='F')
        points = (np.column_stack([world, np.ones(8)]) @ np.linalg.inv(pose).T)[:, :3]
        if points[:, 2].min() <= .05:
            raise ValueError('Crop crosses camera; cannot use simple projected hull')
        fx, fy = meta['focal_length_px']; cx, cy = meta['principal_point_px']
        uv = points[:, :2]/points[:, 2:3]*[fx, fy]+[cx, cy]
        uv *= [width/meta['width'], height/meta['height']]
        mask = np.zeros((height, width), dtype='uint8')
        cv2.fillConvexPoly(mask, cv2.convexHull(uv.astype('int32')), 1)
        mask = mask.astype(bool)
        row = {'view': name, 'size': [width, height], 'region_pixels': int(mask.sum())}
        for key, image in [('baseline', a), ('trial', b)]:
            row[key] = {'whole': score(source, image, np.ones_like(mask)), 'crop_region': score(source, image, mask)}
        rows.append(row)
    summary = {run: {region: {metric: float(np.mean([r[run][region][metric] for r in rows])) for metric in ('psnr_db', 'ssim')} for region in ('whole', 'crop_region')} for run in ('baseline', 'trial')}
    return {'views': rows, 'means': summary, 'scope': 'Common held-out views, equal image resolution. Fixed 3D crop projection includes background; no semantic mask; no claim of independent-object generalization.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('capture'); p.add_argument('baseline'); p.add_argument('trial'); p.add_argument('crop'); p.add_argument('--report', type=Path, required=True)
    a = p.parse_args(); result = compare(a.capture, a.baseline, a.trial, a.crop)
    a.report.write_text(json.dumps(result, indent=2), encoding='utf-8'); print(json.dumps(result['means'], indent=2))
