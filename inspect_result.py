"""Use Brush's native renderer to inspect existing PLY at recorded viewpoints.

Brush 0.3.0 has no render-only CLI. Load the PLY as initialization, take one
effectively frozen step, then export native eval images. Verify all exported
Gaussian fields agree within float32 serialization tolerance (1e-6).
These are training-view checks, not held-out reconstruction metrics.
"""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil

import numpy as np
import reconstruct as pipeline


def read_gaussians(path):
    types = {"float": "<f4", "float32": "<f4", "double": "<f8", "uchar": "u1"}
    fields = []
    with Path(path).open("rb") as stream:
        in_vertex = False
        count = None
        while True:
            line = stream.readline()
            if not line:
                raise ValueError("Incomplete PLY header")
            parts = line.decode("ascii").split()
            if not parts:
                continue
            if parts[0] == "end_header":
                break
            if parts[0] == "element":
                in_vertex = parts[1] == "vertex"
                if in_vertex:
                    count = int(parts[2])
            if parts[0] == "property" and in_vertex:
                fields.append((parts[2], types[parts[1]]))
        return np.fromfile(stream, dtype=np.dtype(fields), count=count)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    parser.add_argument("--model", type=Path, help="Inspect a cropped Gaussian PLY using the original cameras")
    args = parser.parse_args()
    root = args.run.resolve()
    source_model = args.model.resolve() if args.model else root / "model.ply"
    info = pipeline.gaussian_info(source_model)
    state = json.loads((root / "run.json").read_text(encoding="utf-8"))
    if state["status"] != "completed":
        raise ValueError("Only completed runs can be inspected")
    tools = pipeline.Tools(json.loads((pipeline.ROOT / "tools.local.json").read_text(encoding="utf-8")))
    report_root = source_model.parent
    destination = report_root / "inspection" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    dataset = destination / "dataset"
    for name in ("images", "sparse/0"):
        (dataset / name).mkdir(parents=True)
        for source in (root / "dataset" / name).iterdir():
            if source.is_file():
                target = dataset / name / source.name
                try:
                    os.link(source, target)
                except OSError:
                    shutil.copyfile(source, target)
    shutil.copyfile(source_model, dataset / "init.ply")
    exported = destination / "native"
    exported.mkdir()
    command = tools.command("brush") + [str(dataset), "--total-steps", "1", "--sh-degree", "2",
        "--max-resolution", "1024", "--eval-split-every", "25", "--eval-every", "1",
        "--eval-save-to-disk", "--export-every", "1", "--export-path", str(exported),
        "--export-name", "checked_{iter}.ply", "--lr-mean", "1e-30", "--lr-mean-end", "1e-30",
        "--lr-scale", "1e-30", "--lr-scale-end", "1e-30", "--lr-coeffs-dc", "0",
        "--lr-opac", "0", "--lr-rotation", "0", "--mean-noise-weight", "0",
        "--refine-every", "100000", "--seed", "42"]
    pipeline.run_logged(command, destination / "render.log", cwd=destination)
    before = read_gaussians(source_model)
    after = read_gaussians(exported / "checked_1.ply")
    same_layout = before.dtype == after.dtype and before.shape == after.shape
    differences = {name: float(np.max(np.abs(before[name] - after[name])))
                   for name in before.dtype.names} if same_layout else {}
    close = same_layout and all(np.isfinite(value) and value <= 1e-6 for value in differences.values())
    metrics = re.search(r"Eval iter 1: PSNR ([\d.]+), ssim ([\d.]+)",
                        (destination / "render.log").read_text(encoding="utf-8", errors="replace"))
    report = {"checked_at": pipeline.utc_now(), "source_model": str(source_model), **info,
              "gaussian_fields_match_atol_1e_6": close,
              "max_absolute_parameter_difference": max(differences.values(), default=None),
              "parameter_differences": {name: value for name, value in differences.items() if value},
              "training_view_psnr": float(metrics[1]) if metrics else None,
              "training_view_ssim": float(metrics[2]) if metrics else None,
              "images": [str(path) for path in sorted((exported / "eval_1").glob("*.png"))],
              "scope": "Native Brush renders at four original training camera viewpoints; not held-out metrics.",
              "log": str(destination / "render.log")}
    if args.model:
        report["metrics_note"] = ("Comparison uses original full-scene photographs, including the removed background. "
                                  "PSNR/SSIM are not meaningful as isolated-object quality scores.")
    pipeline.write_json(destination / "report.json", report)
    pipeline.write_json(report_root / "inspection-report.json", report)
    if not close:
        raise ValueError("Inspection changed Gaussian fields beyond 1e-6; inspect report before using images")
    if len(report["images"]) != 4:
        raise ValueError("Expected four viewpoint renders")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    raise SystemExit(main())
