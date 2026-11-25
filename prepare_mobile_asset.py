"""Prepare this capture's metric transform without rewriting Gaussian attributes.

The original camera roll is only an initial up estimate. RANSAC fits nearby
floor Gaussians; a robust top percentile and the user's measured total height
set uniform scale. This is an approximate calibration, not a surveyed model.
The renderer must apply this similarity to the complete splat object, including
covariances and SH view directions, rather than changing positions alone.
"""

import argparse
import json
from pathlib import Path
import shutil

import numpy as np

import reconstruct as pipeline
from extract_chair import read_poses
from inspect_result import read_gaussians


def positions(records):
    return np.column_stack([records[key] for key in ("x", "y", "z")]).astype(float)


def fit_floor(chair, room, room_opacity, poses):
    up = np.median([-rotation[1] for rotation, _, _ in poses.values()], axis=0)
    up /= np.linalg.norm(up)
    height = chair @ up
    span = np.ptp(height)
    delta = room - np.median(chair, axis=0)
    radial = np.linalg.norm(delta - np.outer(delta @ up, up), axis=1)
    candidate = ((room @ up < np.percentile(height, 8)) &
                 (room @ up > height.min() - .25 * span) &
                 (radial < span * 1.4) & (room_opacity > 0))
    points = room[candidate]
    if len(points) < 50:
        raise ValueError("Too few nearby floor candidates")
    rng = np.random.default_rng(42)
    tolerance = span * .012
    best = None
    for _ in range(1200):
        sample = points[rng.choice(len(points), 3, replace=False)]
        normal = np.cross(sample[1] - sample[0], sample[2] - sample[0])
        norm = np.linalg.norm(normal)
        if norm < 1e-9:
            continue
        normal /= norm
        if normal @ up < 0:
            normal = -normal
        if normal @ up < np.cos(np.radians(30)):
            continue
        offset = sample[0] @ normal
        inside = np.abs(points @ normal - offset) < tolerance
        if best is None or inside.sum() > best.sum():
            best = inside
    if best is None or best.sum() < 50:
        raise ValueError("No supported floor plane")
    for _ in range(3):
        centre = points[best].mean(0)
        _, _, axes = np.linalg.svd(points[best] - centre, full_matrices=False)
        normal = axes[-1]
        normal *= np.sign(normal @ up)
        offset = centre @ normal
        best = np.abs(points @ normal - offset) < tolerance
    return normal, offset, {
        "candidates": len(points), "inliers": int(best.sum()),
        "median_residual_source_units": float(np.median(np.abs(points[best] @ normal - offset))),
        "up_correction_degrees": float(np.degrees(np.arccos(np.clip(normal @ up, -1, 1))))}


def make_transform(chair, up, floor_offset, reference_camera, height_m):
    # +Z is the horizontal direction towards capture frame 1, not a claim
    # about which side of the physical chair is its front.
    forward = reference_camera - np.median(chair, axis=0)
    forward -= up * (forward @ up)
    forward /= np.linalg.norm(forward)
    right = np.cross(up, forward)
    rotation = np.stack([right, up, forward])
    horizontal = chair @ rotation.T
    centre = (np.percentile(horizontal[:, [0, 2]], 1, axis=0) +
              np.percentile(horizontal[:, [0, 2]], 99, axis=0)) / 2
    top = float(np.percentile(chair @ up - floor_offset, 99.8))
    if top <= 0 or height_m <= 0:
        raise ValueError("Height must be positive")
    scale = height_m / top
    translation = -scale * np.array([centre[0], floor_offset, centre[1]])
    matrix = np.eye(4)
    matrix[:3, :3] = rotation * scale
    matrix[:3, 3] = translation
    return matrix, rotation, scale, translation


def prepare(run, height_m, output, chair=None):
    run, output = Path(run), Path(output)
    if not np.isfinite(height_m) or height_m <= 0:
        raise ValueError("Height must be positive and finite")
    source = Path(chair) if chair is not None else run / "chair.ply"
    chair_records = read_gaussians(source)
    chair = positions(chair_records)
    room_records = read_gaussians(run / "model.ply")
    poses = read_poses(run / "dataset/sparse/0/images.bin")
    normal, offset, plane_stats = fit_floor(chair, positions(room_records), room_records["opacity"], poses)
    first_rotation, first_translation, _ = poses["frame_0001.jpg"]
    camera = -first_rotation.T @ first_translation
    matrix, rotation, scale, translation = make_transform(chair, normal, offset, camera, height_m)
    metric = chair @ matrix[:3, :3].T + translation
    inverse = np.linalg.inv(matrix)
    roundtrip = metric @ inverse[:3, :3].T + inverse[:3, 3]
    if not np.allclose(rotation @ rotation.T, np.eye(3), atol=1e-10) or not np.isclose(np.linalg.det(rotation), 1):
        raise ValueError("Invalid proper rotation")
    if not np.allclose(roundtrip, chair, atol=1e-10):
        raise ValueError("Transform roundtrip failed")
    if not np.isclose(np.percentile(metric[:, 1], 99.8), height_m):
        raise ValueError("Height calibration failed")
    report = {
        "schema_version": 1, "created_at": pipeline.utc_now(),
        "target_phone": "Xiaomi 17 Pro", "model_file": "chair.ply",
        "source_sha256": pipeline.file_hash(source), "gaussians": len(chair),
        "measured_total_height_m": height_m,
        "coordinate_contract": "p_metric = scale * rotation * p_ply + translation; column vectors; metres; +Y up; floor Y=0; +Z towards frame 1 camera",
        "rotation_rows": rotation.tolist(), "uniform_scale": scale,
        "translation_m": translation.tolist(), "source_to_metric_rows": matrix.tolist(),
        "metric_to_source_rows": inverse.tolist(),
        "floor_normal_source": normal.tolist(), "floor_offset_source": float(offset),
        "floor_fit": plane_stats,
        "floor_residual_median_m": plane_stats["median_residual_source_units"] * scale,
        "metric_centre_bounds_m": [metric.min(0).tolist(), metric.max(0).tolist()],
        "calibration": "Nearby-floor RANSAC + 99.8th percentile Gaussian-centre top; supplied measured_total_height_m. Approximate; centres do not bound rendered ellipsoids.",
        "renderer_contract": "Keep PLY unchanged and apply object transform to complete Gaussian renderer. Do not transform just xyz: covariance axes and local-space SH viewing directions must follow. Verify importer axis conversion before applying this transform.",
        "limitations": ["Foot halos and edge blur remain; no new cleanup in this step.",
                        "Metric dimensions depend on estimated floor/top and supplied height; not metrology.",
                        "Mobile rendering and AR require device testing; no APK supplied by this step."],
        "checks": {"proper_rotation": True, "inverse_roundtrip_max_error": float(np.abs(roundtrip-chair).max()),
                   "calibrated_top_m": float(np.percentile(metric[:, 1], 99.8))}}
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(source, output / "chair.ply")
    if pipeline.file_hash(output / "chair.ply") != report["source_sha256"]:
        raise ValueError("Copied model differs from source")
    pipeline.write_json(output / "calibration.json", report)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, default=pipeline.ROOT / "runs/furniture-470cc7e52c525dbe")
    parser.add_argument("--chair", type=Path, help="Extracted Gaussian PLY; defaults to run/chair.ply")
    parser.add_argument("--height-m", type=float, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = prepare(args.run, args.height_m, args.output, args.chair)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
