"""Capture-specific multi-view silhouette crop; preserves all Gaussian fields.

Polygons are manual annotations, not a generic semantic segmentation model.
COLMAP poses project each Gaussian centre into the masks. Hidden/unsampled
surfaces cannot be recovered by this operation.
"""

import argparse
from datetime import datetime
import json
from pathlib import Path
import struct

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import reconstruct as pipeline
from inspect_result import read_gaussians


def quaternion_matrix(quaternion):
    w, x, y, z = np.asarray(quaternion) / np.linalg.norm(quaternion)
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                     [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


def read_poses(path):
    poses = {}
    with Path(path).open("rb") as stream:
        count = struct.unpack("<Q", stream.read(8))[0]
        for _ in range(count):
            struct.unpack("<I", stream.read(4))
            pose = struct.unpack("<7d", stream.read(56))
            camera = struct.unpack("<I", stream.read(4))[0]
            name = bytearray()
            while True:
                byte = stream.read(1)
                if not byte:
                    raise ValueError("Incomplete COLMAP image name")
                if byte == b"\0":
                    break
                name.extend(byte)
            points = struct.unpack("<Q", stream.read(8))[0]
            stream.seek(points * 24, 1)
            poses[name.decode("utf-8")] = (quaternion_matrix(pose[:4]), np.array(pose[4:]), camera)
    return poses


def write_subset(source, destination, records):
    header = []
    with Path(source).open("rb") as stream:
        while True:
            line = stream.readline()
            if not line:
                raise ValueError("Missing PLY end_header")
            if line.startswith(b"element vertex "):
                line = f"element vertex {len(records)}\n".encode()
            header.append(line)
            if line.strip() == b"end_header":
                break
    with Path(destination).open("xb") as stream:
        stream.write(b"".join(header))
        stream.write(records.tobytes())
    pipeline.gaussian_info(destination)


def extract(masks):
    config = json.loads(Path(masks).read_text(encoding="utf-8"))
    root = pipeline.ROOT / config["source_run"]
    source = root / "model.ply"
    source_hash = pipeline.file_hash(source)
    destination = root / "extraction" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    destination.mkdir(parents=True)
    (destination / "masks").mkdir()
    records = read_gaussians(source)
    positions = np.column_stack([records[field] for field in ("x", "y", "z")])
    with (root / "dataset/sparse/0/cameras.bin").open("rb") as stream:
        if struct.unpack("<Q", stream.read(8))[0] != 1:
            raise ValueError("This capture-specific extraction expects one camera")
        camera_id, model, width, height = struct.unpack("<iiQQ", stream.read(24))
        if model != 1 or [width, height] != config["size"]:
            raise ValueError("Masks require the matching undistorted PINHOLE camera")
        fx, fy, cx, cy = struct.unpack("<4d", stream.read(32))
    poses = read_poses(root / "dataset/sparse/0/images.bin")
    inside_count = np.zeros(len(records), dtype=np.int32)
    outside_count = np.zeros(len(records), dtype=np.int32)
    view_stats = {}
    for name, polygons in config["views"].items():
        mask_image = Image.new("L", (width, height))
        draw = ImageDraw.Draw(mask_image)
        for polygon in polygons:
            draw.polygon([tuple(point) for point in polygon], fill=255)
        margin = config["dilation_pixels"]
        mask_image = mask_image.filter(ImageFilter.MaxFilter(margin*2+1))
        mask_image.save(destination / "masks" / (Path(name).stem + ".png"))
        overlay = Image.open(root / "dataset/images" / name).convert("RGB")
        tint = Image.new("RGB", overlay.size, (20, 220, 110))
        overlay = Image.composite(Image.blend(overlay, tint, .35), overlay, mask_image)
        overlay.save(destination / "masks" / (Path(name).stem + "_overlay.jpg"))
        rotation, translation, referenced_camera = poses[name]
        if referenced_camera != camera_id:
            raise ValueError("Camera mismatch")
        camera_positions = positions @ rotation.T + translation
        z = camera_positions[:, 2]
        with np.errstate(divide="ignore", invalid="ignore"):
            u = camera_positions[:, 0] / z * fx + cx
            v = camera_positions[:, 1] / z * fy + cy
        finite = np.isfinite(u) & np.isfinite(v) & (z > .001)
        observed = finite & (u >= 0) & (v >= 0) & (u < width) & (v < height)
        selected = np.flatnonzero(observed)
        ix = np.clip(np.nan_to_num(u[selected]).astype(int), 0, width-1)
        iy = np.clip(np.nan_to_num(v[selected]).astype(int), 0, height-1)
        foreground = np.asarray(mask_image)[iy, ix] > 0
        inside_count[selected[foreground]] += 1
        outside_count[selected[~foreground]] += 1
        view_stats[name] = int(foreground.sum())

    # Require consistency in every observed view and at least three supports.
    # Pixels outside a clipped frame are unobserved, not negative foreground.
    keep = (inside_count >= 3) & (outside_count == 0)
    if keep.sum() < 100:
        raise ValueError("Crop retained too few Gaussians; inspect masks and poses")
    # Brush 0.3.0 roundtrip testing exposed corruption of the final SH values
    # for an unaligned row count. Keep the count divisible by four, removing
    # at most three of the least opaque splats. All retained fields stay exact.
    alignment_removed = int(keep.sum() % 4)
    if alignment_removed:
        indices = np.flatnonzero(keep)
        weakest = indices[np.argsort(records["opacity"][indices])[:alignment_removed]]
        keep[weakest] = False
    target = destination / "chair.ply"
    write_subset(source, target, records[keep])
    if pipeline.file_hash(source) != source_hash:
        raise ValueError("Source model unexpectedly changed")
    # Retained records must be byte-for-byte identical to the source subset.
    if read_gaussians(target).tobytes() != records[keep].tobytes():
        raise ValueError("Gaussian attributes changed while exporting")
    report = {"created_at": pipeline.utc_now(), "source": str(source), "source_sha256": source_hash,
              "model": str(target), "input_gaussians": len(records), "retained_gaussians": int(keep.sum()),
              "removed_gaussians": int((~keep).sum()), "view_foreground_candidates": view_stats,
              "alignment_splats_removed": alignment_removed,
              "retained_bounds": [positions[keep].min(0).tolist(), positions[keep].max(0).tolist()],
              "method": "Manual four-view silhouettes; Gaussian-centre visual hull; no retraining.",
              "limitations": "May retain floor near feet or lose ambiguous edge splats; requires rendered inspection.",
              "masks": config}
    pipeline.write_json(destination / "extraction.json", report)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--masks", type=Path, default=pipeline.ROOT / "chair_masks.json")
    args = parser.parse_args()
    report = extract(args.masks)
    print(json.dumps({key: value for key, value in report.items() if key != "masks"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    raise SystemExit(main())
