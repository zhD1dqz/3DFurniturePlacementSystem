"""Real tool/GPU smoke test on generated data, NOT a furniture quality test.

Only this optional developer check needs numpy and Pillow. The pipeline itself
uses the standard library. Every invocation preserves a new folder under runs/.
"""

import json
import sys
from datetime import datetime

import numpy as np
from PIL import Image

import reconstruct as pipeline


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    tools = pipeline.Tools(json.loads((pipeline.ROOT / "tools.local.json").read_text(encoding="utf-8")))
    output = pipeline.ROOT / "runs" / ("environment-smoke-" + datetime.now().strftime("%Y%m%d_%H%M%S_%f"))
    output.mkdir(parents=True)
    dataset = output / "dataset"
    images = dataset / "images"
    text_model = output / "text-model"
    sparse = dataset / "sparse" / "0"
    for folder in (images, text_model, sparse):
        folder.mkdir(parents=True)

    # Four known parallel cameras see the same textured plane at z=3.
    # Camera poses and points are deliberately supplied, not recovered by SfM.
    (text_model / "cameras.txt").write_text("1 PINHOLE 128 128 100 100 64 64\n", encoding="ascii")
    image_lines = []
    for index, center in enumerate((-0.3, -0.1, 0.1, 0.3), 1):
        yy, xx = np.mgrid[:128, :128]
        wx = (xx - 64) * 3 / 100 + center
        wy = (yy - 64) * 3 / 100
        pixels = np.stack([
            (np.sin(wx * 12) + 1) * 100 + 20,
            (np.cos(wy * 9) + 1) * 100 + 20,
            (((np.floor(wx * 5) + np.floor(wy * 5)) % 2) * 180 + 40),
        ], axis=-1).astype(np.uint8)
        filename = f"frame_{index:04d}.jpg"
        Image.fromarray(pixels).save(images / filename)
        image_lines.extend([f"{index} 1 0 0 0 {-center} 0 0 1 {filename}", ""])
    (text_model / "images.txt").write_text("\n".join(image_lines) + "\n", encoding="ascii")
    points = []
    rng = np.random.default_rng(42)
    for index, (x, y) in enumerate(rng.uniform(-1.2, 1.2, (500, 2)), 1):
        points.append(f"{index} {x} {y} 3 128 160 200 0")
    (text_model / "points3D.txt").write_text("\n".join(points) + "\n", encoding="ascii")
    pipeline.run_logged(tools.command("colmap") + ["model_converter", "--input_path", str(text_model),
                        "--output_path", str(sparse), "--output_type", "BIN"], output / "converter.log")
    trained = output / "gaussian"
    trained.mkdir()
    steps = 12
    pipeline.prepare_brush_images(dataset)
    pipeline.run_logged(pipeline.brush_training_command(tools, dataset, trained, steps=steps, resolution=128),
                        output / "training.log", cwd=output)
    info = pipeline.gaussian_info(trained / f"model_{steps}.ply")

    # Verify video encoding, probing and real frame extraction separately.
    video = output / "synthetic.mp4"
    pipeline.run_logged(tools.command("ffmpeg") + ["-hide_banner", "-nostdin", "-n", "-framerate", "2",
                        "-i", str(images / "frame_%04d.jpg"), "-pix_fmt", "yuv420p", str(video)],
                        output / "encode.log")
    metadata = pipeline.probe_video(tools, video)
    frames = output / "extracted"
    frames.mkdir()
    pipeline.run_logged(tools.command("ffmpeg") + ["-hide_banner", "-nostdin", "-n", "-i", str(video),
                        str(frames / "frame_%04d.jpg")], output / "decode.log")
    assert len(list(frames.glob("*.jpg"))) == 4
    # Exercise the actual CPU feature extractor and matcher, without claiming
    # that a generated planar scene validates real furniture camera recovery.
    database = output / "features.db"
    command = tools.command("colmap")
    feature_help = pipeline.capture(command + ["feature_extractor", "-h"], include_stderr=True)
    match_help = pipeline.capture(command + ["exhaustive_matcher", "-h"], include_stderr=True)
    extraction = "FeatureExtraction" if "--FeatureExtraction.use_gpu" in feature_help else "SiftExtraction"
    matching = "FeatureMatching" if "--FeatureMatching.use_gpu" in match_help else "SiftMatching"
    pipeline.run_logged(command + ["feature_extractor", "--database_path", str(database), "--image_path", str(frames),
                        "--ImageReader.single_camera", "1", "--ImageReader.camera_model", "OPENCV",
                        f"--{extraction}.use_gpu", "0"], output / "features.log")
    pipeline.run_logged(command + ["exhaustive_matcher", "--database_path", str(database),
                        f"--{matching}.use_gpu", "0"], output / "matching.log")
    report = {"checked_at": pipeline.utc_now(), "ready": True, "output": str(output),
              "training_steps": steps, "video": metadata, **info,
              "scope": "Generated 128px scene with supplied poses; real GPU training and Gaussian export; "
                       "FFmpeg encode/decode and COLMAP conversion/features/matching. "
                       "Does not validate real furniture SfM, quality, 1024px memory usage, or AR."}
    pipeline.write_json(output / "report.json", report)
    pipeline.write_json(pipeline.ROOT / "smoke-report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    raise SystemExit(main())
