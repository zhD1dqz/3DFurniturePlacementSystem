"""Bundle this capture's existing manual masks and metric reference.

These are capture-specific development inputs, not automatic segmentation.
"""
import gzip
import json
from pathlib import Path
import struct
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]


def prepare(destination):
    from extract_chair import read_poses
    from inspect_result import read_gaussians
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    masks = json.loads((ROOT / "chair_masks.json").read_text(encoding="utf-8"))
    run = ROOT / masks["source_run"]
    state = json.loads((run / "run.json").read_text(encoding="utf-8"))
    calibration = json.loads((ROOT / "mobile-assets/chair-1m/calibration.json").read_text())
    geometry = json.loads((ROOT / "dist/geometry-v03-final/geometry-report.json").read_text())
    poses = read_poses(run / "dataset/sparse/0/images.bin")
    with (run / "dataset/sparse/0/cameras.bin").open("rb") as stream:
        if struct.unpack("<Q", stream.read(8))[0] != 1: raise ValueError("Expected one reference camera")
        camera_id, model, w, h = struct.unpack("<iiQQ", stream.read(24))
        if model != 1 or [w, h] != masks["size"]: raise ValueError("Reference masks/camera mismatch")
        intrinsics = list(struct.unpack("<4d", stream.read(32)))
    angle = np.radians(geometry["heading_refinement_deg"])
    heading = np.array([[np.cos(angle), 0, -np.sin(angle)], [0, 1, 0], [np.sin(angle), 0, np.cos(angle)]])
    rotation = heading @ np.array(calibration["rotation_rows"])
    scale = calibration["uniform_scale"] * geometry["normalization_scale"]
    translation = (heading @ np.array(calibration["translation_m"]) - np.array(geometry["translation_before_normalization"])) * geometry["normalization_scale"]
    rules = {"schema_version": 1, "capture_id": state["fingerprint"]["video_sha256"],
             "description": "Existing chair capture only; manually traced silhouettes and existing metric reference",
             "rotation_rows": rotation.tolist(), "uniform_scale": scale, "translation_m": translation.tolist(),
             "dimensions": {key: geometry[key] for key in ("width_m", "height_m", "depth_m")},
             "views": []}
    mask_arrays = []
    for name, polygons in masks["views"].items():
        image = Image.new("L", (w, h))
        draw = ImageDraw.Draw(image)
        for polygon in polygons: draw.polygon([tuple(p) for p in polygon], fill=255)
        image = image.filter(ImageFilter.MaxFilter(masks["dilation_pixels"] * 2 + 1))
        filename = Path(name).stem + ".mask.gz"
        data = (np.asarray(image) > 0).astype(np.uint8)
        with gzip.open(destination / filename, "wb") as stream:
            stream.write(struct.pack("<II", w, h)); stream.write(data.tobytes())
        r, t, cid = poses[name]
        if cid != camera_id: raise ValueError("Reference camera mismatch")
        rules["views"].append({"file":filename,"rotation_rows":r.tolist(),"translation":t.tolist(),"intrinsics":intrinsics})
        mask_arrays.append((r,t,data))
    (destination / "capture-rules.json").write_text(json.dumps(rules, indent=2), encoding="utf-8")
    # Independent reference expected by Android integration tests.
    source = ROOT / "mobile-reconstruction/results/20261101_175731_312302/generated_1200.ply"
    if source.exists():
        records = read_gaussians(source)
        xyz = np.column_stack([records[k] for k in ("x", "y", "z")]).astype(float)
        inside = np.zeros(len(xyz), dtype=int); outside = inside.copy()
        fx,fy,cx,cy = intrinsics
        for r,t,mask in mask_arrays:
            p = xyz @ r.T + t
            with np.errstate(divide="ignore",invalid="ignore"):
                u = p[:,0] / p[:,2] * fx + cx; v = p[:,1] / p[:,2] * fy + cy
            observed = np.isfinite(u) & np.isfinite(v) & (p[:,2]>.001) & (u>=0) & (v>=0) & (u<w) & (v<h)
            ids = np.flatnonzero(observed)
            foreground = mask[v[ids].astype(int),u[ids].astype(int)] > 0
            inside[ids[foreground]] += 1; outside[ids[~foreground]] += 1
        keep = np.flatnonzero((inside>=3)&(outside==0))
        import hashlib
        reference = {"source_sha256":hashlib.sha256(source.read_bytes()).hexdigest(), "source_gaussians":len(records),
                     "retained_gaussians":len(keep),"retained_source_indices_sha256":hashlib.sha256(keep.astype('<i4').tobytes()).hexdigest()}
        (destination / "reference-check.json").write_text(json.dumps(reference,indent=2),encoding="utf-8")
    return rules
