"""Audit phone-only detail training with equal-resolution held-out-view comparisons."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from compare_region_quality import score

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from inspect_result import read_gaussians


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('capture', type=Path)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('trial', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    runs, folders, audit = {}, {}, {}
    for key, path in [('baseline', args.baseline), ('trial', args.trial)]:
        result = json.loads((path/'integration-result.json').read_text(encoding='utf-8'))
        assert result['phase'] == 'completed'
        assert result['completed_steps'] == result['requested_steps'] or result['stopped_for_time_budget']
        assert result['capture_id'] == args.capture.name
        records = read_gaussians(path/result['model'])
        assert len(records) == result['gaussians']
        assert all(np.isfinite(records[k]).all() for k in records.dtype.names)
        assert np.all(np.linalg.norm(np.column_stack([records[f'rot_{i}'] for i in range(4)]), axis=1) > 1e-8)
        runs[key] = result
        folders[key] = path/f"eval_{result['completed_steps']}"
        audit[key] = {'gaussians': len(records), 'all_fields_finite': True,
                      'steps': result['completed_steps'], 'seconds': result['generation_and_prepare_ms']/1000,
                      'ply_sha256': hashlib.sha256((path/result['model']).read_bytes()).hexdigest()}
    common = sorted(set(p.name for p in folders['baseline'].glob('frame-*.png')) &
                    set(p.name for p in folders['trial'].glob('frame-*.png')))
    assert len(common) >= 5, 'Missing held-out renders'
    rows = []
    for name in common:
        original = cv2.imread(str(args.capture/(Path(name).stem+'.jpg')))
        images = {key: cv2.imread(str(folder/name)) for key, folder in folders.items()}
        w = min(original.shape[1], *(image.shape[1] for image in images.values()))
        h = round(original.shape[0]*w/original.shape[1])
        original = cv2.resize(original, (w, h), interpolation=cv2.INTER_AREA).astype(float)
        whole = np.ones((h, w), dtype=bool)
        center = np.zeros((h, w), dtype=bool)
        center[int(.15*h):int(.85*h), int(.15*w):int(.85*w)] = True
        row = {'view': name, 'size': [w, h]}
        for key, image in images.items():
            image = cv2.resize(image, (w, h), interpolation=cv2.INTER_AREA).astype(float)
            row[key] = {'whole': score(original, image, whole), 'center_70pct': score(original, image, center)}
        rows.append(row)
    means = {key: {region: {metric: float(np.mean([row[key][region][metric] for row in rows]))
                            for metric in ('psnr_db', 'ssim')}
                   for region in ('whole', 'center_70pct')} for key in runs}
    report = {'artifacts': audit, 'evaluation_views': len(rows), 'means': means, 'views': rows,
              'scope': 'Same capture images, Gaussian-training held-out views at equal resolution. Pose provenance is recorded in each input result. Center rectangle includes background and is not a furniture mask. Localization may use all views. One run per preset; not independent-scene generalization or proof of perceived sharpness.'}
    (args.output/'quality-summary.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 19)
    sheet = Image.new('RGB', (384*3, 512*3+52), (16, 25, 31))
    draw = ImageDraw.Draw(sheet)
    trial_label = 'Computer' if runs['trial'].get('source') == 'computer' else 'Updated'
    for x, title in enumerate(['Original 640x480 capture', f"Phone: {runs['baseline']['completed_steps']} steps", f"{trial_label}: {runs['trial']['completed_steps']} steps"]):
        draw.text((x*384+10, 12), title, fill='white', font=font)
    for y, name in enumerate([common[0], common[len(common)//2], common[-1]]):
        paths = [args.capture/(Path(name).stem+'.jpg'), folders['baseline']/name, folders['trial']/name]
        for x, path in enumerate(paths):
            image = cv2.imread(str(path))
            image = cv2.resize(image, (512, 384), interpolation=cv2.INTER_AREA)
            image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
            sheet.paste(Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB)), (x*384, 52+y*512))
    sheet.save(args.output/'detail-comparison.png')
    print(json.dumps({'artifacts': audit, 'means': means}, indent=2))


if __name__ == '__main__':
    main()
