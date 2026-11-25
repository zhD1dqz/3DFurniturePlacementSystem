"""Development QA: compare recorded poses with independent image feature matches.

This does not refine poses or claim metric ground truth. No image rotation is applied.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def intrinsic(frame):
    fx, fy = frame['focal_length_px']
    cx, cy = frame['principal_point_px']
    return np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1.]])


def assess(folder):
    folder = Path(folder)
    files = sorted(folder.glob('frame-*.json'))
    detector = cv2.SIFT_create(nfeatures=1600)
    matcher = cv2.BFMatcher()
    features = {}
    def load(index):
        if index not in features:
            frame = json.loads(files[index].read_text(encoding='utf-8'))
            gray = cv2.imread(str(folder/frame['image']), cv2.IMREAD_GRAYSCALE)
            keypoints, descriptors = detector.detectAndCompute(gray, None)
            features[index] = frame, keypoints, descriptors
        return features[index]
    pairs = []
    cv2.setRNGSeed(42)
    for first in range(0, len(files)-1, 5):
        second = first+1
        a, ka, da = load(first)
        b, kb, db = load(second)
        if da is None or db is None:
            pairs.append({'first': first, 'second': second, 'status': 'no_features'})
            continue
        matches = [m for m, n in matcher.knnMatch(da, db, k=2) if m.distance < .75*n.distance]
        if len(matches) < 12:
            pairs.append({'first': first, 'second': second, 'status': 'too_few_matches', 'matches': len(matches)})
            continue
        x = np.float32([ka[m.queryIdx].pt for m in matches])
        y = np.float32([kb[m.trainIdx].pt for m in matches])
        _, mask = cv2.findFundamentalMat(x, y, cv2.FM_RANSAC, 1.5, .999)
        if mask is None:
            continue
        keep = mask.ravel().astype(bool)
        ca = np.array(a['camera_to_world_cv_column_major']).reshape(4, 4, order='F')
        cb = np.array(b['camera_to_world_cv_column_major']).reshape(4, 4, order='F')
        relative = np.linalg.inv(cb) @ ca
        t = relative[:3, 3]
        skew = np.array([[0, -t[2], t[1]], [t[2], 0, -t[0]], [-t[1], t[0], 0]])
        fundamental = np.linalg.inv(intrinsic(b)).T @ skew @ relative[:3, :3] @ np.linalg.inv(intrinsic(a))
        xh = np.column_stack([x, np.ones(len(x))]); yh = np.column_stack([y, np.ones(len(y))])
        fx = xh @ fundamental.T; fty = yh @ fundamental
        numerator = np.sum(yh*fx, axis=1)**2
        denominator = np.sum(fx[:, :2]**2, axis=1)+np.sum(fty[:, :2]**2, axis=1)
        error = np.sqrt(numerator/np.maximum(denominator, 1e-30))[keep]
        pairs.append({'first': first, 'second': second, 'status': 'measured' if int(keep.sum()) >= 20 else 'weak_matches',
                      'matches': len(matches), 'image_ransac_inliers': int(keep.sum()),
                      'camera_baseline_m': float(np.linalg.norm(t)),
                      'pose_sampson_median_px': float(np.median(error)),
                      'pose_sampson_p90_px': float(np.percentile(error, 90))})
    measured = [p for p in pairs if p['status']=='measured']
    return {'capture': folder.name, 'pairs': pairs,
            'median_of_pair_medians_px': float(np.median([p['pose_sampson_median_px'] for p in measured])) if measured else None,
            'scope': 'Offline development QA only; independent image RANSAC selects likely correspondences, then recorded AR poses predict epipolar error. Does not validate absolute size, target isolation, complete coverage or reconstruction quality.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = assess(args.capture)
    text = json.dumps(report, indent=2)
    if args.report:
        args.report.write_text(text, encoding='utf-8')
    print(text)
