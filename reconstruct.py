"""Video -> COLMAP -> Brush or Graphdeco 3DGS (Python stdlib only)."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parent
PRESET = {
    "target_frames": 100,
    "max_image_size": 1024,
    "min_frames": 30,
    "min_registered_ratio": 0.75,
    "iterations": 7000,
    "sh_degree": 2,
    "densify_until_iter": 3000,
    "densification_interval": 200,
    "densify_grad_threshold": 0.0005,
    "brush_max_splats": 500000,
    "brush_growth_stop_iter": 4000,
    "brush_growth_grad_threshold": 0.00004,
    "camera_model": "SIMPLE_RADIAL",
    "sift_peak_threshold": 0.002,
    "sift_domain_size_pooling": 1,
    "sift_estimate_affine_shape": 1,
    "guided_matching": 1,
}
STAGES = ("frames", "colmap", "train")


class PipelineError(Exception):
    pass


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, data):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def executable(value, default):
    name = value or default
    expanded = Path(name).expanduser()
    # Config paths are relative to the project, not to whichever terminal is used.
    local = expanded if expanded.is_absolute() else ROOT / expanded
    found = str(local.resolve()) if local.is_file() else shutil.which(name)
    if not found:
        raise PipelineError(f"找不到 {default}：{name}。请安装工具并设置 tools.local.json。")
    if Path(found).suffix.lower() in {".bat", ".cmd"}:
        # Run the native COLMAP executable with its DLL paths; never shell-expand user paths.
        native = Path(found).parent / "bin" / "colmap.exe"
        if default != "colmap" or not native.is_file():
            raise PipelineError(f"请配置原生可执行文件，不能直接使用批处理文件：{found}")
        found = str(native)
    return found


class Tools:
    def __init__(self, config):
        self.config = config

    def command(self, name):
        return [executable(self.config.get(name), name)]

    def trainer(self):
        repo = Path(self.config.get("gs_repo") or "third_party/gaussian-splatting").expanduser()
        if not repo.is_absolute():
            repo = ROOT / repo
        repo = repo.resolve()
        if not (repo / "train.py").is_file():
            raise PipelineError(f"找不到现成的 3DGS 实现：{repo / 'train.py'}。见 README.md。")
        python = executable(self.config.get("training_python") or sys.executable, "python")
        return repo, [python]


def tool_environment(command):
    env = os.environ.copy()
    parent = Path(command[0]).parent
    # Windows official COLMAP package ships dependent DLLs beside its launcher/bin/lib.
    directories = [parent, parent.parent / "lib"]
    env["PATH"] = os.pathsep.join(str(p) for p in directories if p.is_dir()) + os.pathsep + env.get("PATH", "")
    plugins = parent.parent / "plugins"
    if plugins.is_dir():
        env["QT_PLUGIN_PATH"] = str(plugins)
    env["PYTHONUNBUFFERED"] = "1"
    env["PYTHONIOENCODING"] = "utf-8"
    return env


def capture(command, cwd=None, timeout=60, include_stderr=False):
    try:
        result = subprocess.run(command, cwd=cwd, env=tool_environment(command),
                                capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PipelineError(f"无法执行 {command[0]}：{exc}") from exc
    if result.returncode:
        raise PipelineError(f"命令失败（{result.returncode}）：{result.stderr[-3000:]} {result.stdout[-1000:]}")
    return result.stdout + result.stderr if include_stderr else result.stdout


def run_logged(command, log, cwd=None):
    log.parent.mkdir(parents=True, exist_ok=True)
    print(f"  正在执行 {Path(command[0]).name}，日志：{log}", flush=True)
    with log.open("w", encoding="utf-8") as stream:
        stream.write(json.dumps({"argv": command, "cwd": str(cwd) if cwd else None}, ensure_ascii=False) + "\n")
        stream.flush()
        try:
            process = subprocess.Popen(command, cwd=cwd, env=tool_environment(command),
                                       stdout=stream, stderr=subprocess.STDOUT)
            try:
                code = process.wait()
            except KeyboardInterrupt:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                raise
        except OSError as exc:
            raise PipelineError(f"无法启动工具：{exc}；日志：{log}") from exc
    if code:
        tail = log.read_text(encoding="utf-8", errors="replace")[-3000:]
        hint = "\n显存不足时，请关闭占用 GPU 的软件；当前参数不能保证适合所有素材。" if "out of memory" in tail.lower() else ""
        raise PipelineError(f"命令退出码 {code}，日志：{log}\n{tail}{hint}")


def probe_video(tools, video):
    raw = capture(tools.command("ffprobe") + ["-v", "error", "-select_streams", "v:0",
                  "-show_entries", "stream=width,height,duration,avg_frame_rate:format=duration",
                  "-of", "json", str(video)])
    try:
        data = json.loads(raw)
        stream = data["streams"][0]
        duration = float(stream.get("duration") or data.get("format", {}).get("duration"))
        width, height = int(stream["width"]), int(stream["height"])
        if not math.isfinite(duration) or duration <= 0 or width <= 0 or height <= 0:
            raise ValueError("invalid video dimensions/duration")
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise PipelineError("视频没有可读取的画面或有效时长，请使用普通 MP4/MOV 视频。") from exc
    return {"duration_seconds": duration, "width": width, "height": height}


def binary_count(path):
    with Path(path).open("rb") as stream:
        header = stream.read(8)
    if len(header) != 8:
        raise PipelineError(f"COLMAP 输出不完整：{path}")
    return struct.unpack("<Q", header)[0]


def choose_sparse_model(directory, total_frames):
    candidates = []
    for model in Path(directory).iterdir():
        if model.is_dir() and all((model / f"{name}.bin").is_file() for name in ("cameras", "images", "points3D")):
            candidates.append((binary_count(model / "images.bin"), model))
    if not candidates:
        raise PipelineError("COLMAP 未生成有效稀疏模型。请检查模糊、纹理和视角重叠；见 mapper 日志。")
    count, best = max(candidates, key=lambda item: item[0])
    if count < math.ceil(total_frames * PRESET["min_registered_ratio"]):
        raise PipelineError(f"最佳模型只定位了 {count}/{total_frames} 张图片，低于 75% 门槛。请先检查拍摄素材，暂不训练。")
    if binary_count(best / "points3D.bin") == 0:
        raise PipelineError("COLMAP 模型没有三维点，无法初始化 3DGS。")
    return best, count


def gaussian_info(path):
    sizes = {"float": 4, "float32": 4, "double": 8, "float64": 8, "uchar": 1, "uint8": 1}
    fields, stride, count, format_name = set(), 0, None, None
    in_vertex = False
    with Path(path).open("rb") as stream:
        if stream.readline().strip() != b"ply":
            raise PipelineError("训练输出不是 PLY 文件。")
        for _ in range(256):
            line = stream.readline(4096)
            if not line:
                raise PipelineError("PLY 文件头不完整。")
            parts = line.decode("ascii").strip().split()
            if not parts:
                continue
            if parts[0] == "end_header":
                payload_offset = stream.tell()
                break
            if parts[0] == "format":
                format_name = parts[1]
            elif parts[0] == "element":
                in_vertex = parts[1] == "vertex"
                if in_vertex:
                    count = int(parts[2])
            elif parts[0] == "property" and in_vertex:
                if len(parts) != 3 or parts[1] not in sizes:
                    raise PipelineError("不支持的 Gaussian PLY 属性类型。")
                fields.add(parts[2])
                stride += sizes[parts[1]]
        else:
            raise PipelineError("PLY 文件头过长或缺少 end_header。")
    required = {"x", "y", "z", "opacity", "f_dc_0", "f_dc_1", "f_dc_2",
                "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"}
    if not count or not required.issubset(fields):
        raise PipelineError("输出缺少 Gaussian 属性或顶点为空，普通点云不能替代高斯模型。")
    if format_name != "binary_little_endian":
        raise PipelineError("当前出口要求 binary_little_endian Gaussian PLY。")
    size = Path(path).stat().st_size
    if size < payload_offset + count * stride:
        raise PipelineError("高斯模型数据不完整，请检查训练是否中断。")
    return {"gaussian_count": count, "model_size_bytes": size}


def training_check(repo, python):
    code = (
        "import json, torch; import diff_gaussian_rasterization, simple_knn._C; "
        "assert torch.cuda.is_available(), 'PyTorch cannot access CUDA'; "
        "x=torch.ones(1, device='cuda'); torch.cuda.synchronize(); "
        "print(json.dumps({'torch':torch.__version__,'cuda':torch.version.cuda,"
        "'gpu':torch.cuda.get_device_name(0)}))"
    )
    return capture(python + ["-c", code], cwd=repo, timeout=120).strip()


def trainer_backend(tools):
    backend = getattr(tools, "config", {}).get("trainer", "graphdeco")
    if backend not in {"brush", "graphdeco"}:
        raise PipelineError(f"不支持的训练器：{backend}")
    return backend


def check_training_tools(tools):
    if trainer_backend(tools) == "brush":
        # This checks the executable, not GPU training. See the separate smoke report.
        return capture(tools.command("brush") + ["--version"]).strip()
    return training_check(*tools.trainer())


def brush_training_command(tools, dataset, trained, steps=None, resolution=None):
    steps = steps if steps is not None else PRESET["iterations"]
    return tools.command("brush") + [str(dataset), "--total-steps", str(steps),
            "--max-resolution", str(resolution or PRESET["max_image_size"]),
            "--sh-degree", str(PRESET["sh_degree"]),
            "--max-splats", str(PRESET["brush_max_splats"]),
            "--growth-stop-iter", str(PRESET["brush_growth_stop_iter"]),
            "--growth-grad-threshold", str(PRESET["brush_growth_grad_threshold"]),
            "--refine-every", str(PRESET["densification_interval"]),
            "--export-every", str(steps), "--export-path", str(trained),
            "--export-name", "model_{iter}.ply", "--seed", "42"]


def prepare_brush_images(dataset):
    # Brush v0.3.0 reads exactly 16387 bytes before decoding image dimensions.
    # Valid smaller JPEGs otherwise fail with UnexpectedEof. Padding after JPEG's
    # end marker leaves decoded pixels and COLMAP camera coordinates unchanged.
    padded = 0
    for image in (Path(dataset) / "images").glob("*.jpg"):
        missing = 16387 - image.stat().st_size
        if missing > 0:
            with image.open("ab") as stream:
                stream.write(b"\0" * missing)
            padded += 1
    return padded


@contextmanager
def run_lock(output):
    path = output / ".run.lock"
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as exc:
        raise PipelineError("此输出目录已有运行锁。确认没有任务运行后，才可手动移除 .run.lock。") from exc
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(str(os.getpid()))
        yield
    finally:
        path.unlink(missing_ok=True)


def execute_pipeline(args, tools):
    video = Path(args.video).expanduser().resolve()
    output = Path(args.output).expanduser().resolve()
    if not video.is_file():
        raise PipelineError(f"视频不存在：{video}")
    # Each run owns its output folder and never deletes earlier files.
    if output.exists() and not args.resume and any(output.iterdir()):
        raise PipelineError("输出目录非空。请选择新目录，或对同一素材使用 --resume。")
    if args.resume and not (output / "run.json").is_file():
        raise PipelineError("没有可继续的 run.json，请选择新目录运行。")
    output.mkdir(parents=True, exist_ok=True)
    with run_lock(output):
        fingerprint = {"video_sha256": file_hash(video), "preset": PRESET,
                       "trainer": trainer_backend(tools)}
        if args.resume:
            state = json.loads((output / "run.json").read_text(encoding="utf-8"))
            if state["fingerprint"] != fingerprint:
                raise PipelineError("视频内容或固定参数已改变，请使用新的输出目录。")
        else:
            state = {"created_at": utc_now(), "video": str(video), "fingerprint": fingerprint,
                     "completed_stages": [], "metrics": {}, "attempts": []}
        attempt = output / "work" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        attempt.mkdir(parents=True)
        state["attempts"].append(str(attempt.relative_to(output)))
        state["error"] = None
        total_started = time.monotonic()
        try:
            for stage in STAGES[:STAGES.index(args.stop_after) + 1]:
                if stage in state["completed_stages"]:
                    validate_stage(stage, output)
                    print(f"跳过已完成阶段：{stage}", flush=True)
                    continue
                state.update(status="running", stage=stage, updated_at=utc_now())
                write_json(output / "run.json", state)
                print(f"阶段：{stage}", flush=True)
                started = time.monotonic()
                if stage == "frames":
                    metadata = probe_video(tools, video)
                    folder = attempt / "frames"
                    folder.mkdir()
                    fps = PRESET["target_frames"] / metadata["duration_seconds"]
                    # Respect video rotation, preserve aspect ratio, and avoid upscaling.
                    limit = PRESET["max_image_size"]
                    filters = f"fps={fps:.12g},scale=w='min(iw,{limit})':h='min(ih,{limit})':force_original_aspect_ratio=decrease"
                    run_logged(tools.command("ffmpeg") + ["-hide_banner", "-nostdin", "-n", "-i", str(video),
                               "-map", "0:v:0", "-vf", filters, "-frames:v", str(PRESET["target_frames"]),
                               "-q:v", "2", str(folder / "frame_%04d.jpg")], attempt / "frames.log")
                    frames = list(folder.glob("frame_*.jpg"))
                    if len(frames) < PRESET["min_frames"] or any(p.stat().st_size == 0 for p in frames):
                        raise PipelineError("有效抽帧不足 30 张或包含空文件，请检查视频。")
                    folder.rename(output / "frames")
                    state["metrics"].update(metadata, frame_count=len(frames))
                elif stage == "colmap":
                    state["metrics"].update(reconstruct_colmap(tools, output, attempt))
                else:
                    state["metrics"].update(train_gaussians(tools, output, attempt))
                state["metrics"][f"{stage}_seconds"] = round(time.monotonic() - started, 3)
                state["completed_stages"].append(stage)
                write_json(output / "run.json", state)
            state.update(status="completed" if "train" in state["completed_stages"] else "ready",
                         updated_at=utc_now(), last_attempt_seconds=round(time.monotonic() - total_started, 3))
            if state["status"] == "completed":
                state["model_path"] = str(output / "model.ply")
        except (Exception, KeyboardInterrupt) as exc:
            state.update(status="interrupted" if isinstance(exc, KeyboardInterrupt) else "failed",
                         error=str(exc) or "用户中断", updated_at=utc_now())
            write_json(output / "run.json", state)
            raise
        write_json(output / "run.json", state)
        print(f"完成：{state['status']}；记录：{output / 'run.json'}", flush=True)
        return state


def validate_stage(stage, output):
    if stage == "frames":
        files = list((output / "frames").glob("frame_*.jpg"))
        valid = len(files) >= PRESET["min_frames"] and all(p.stat().st_size > 0 for p in files)
    elif stage == "colmap":
        valid = (output / "dataset" / "images").is_dir() and all(
            (output / "dataset" / "sparse" / "0" / f"{name}.bin").is_file()
            for name in ("cameras", "images", "points3D"))
    else:
        gaussian_info(output / "model.ply")
        valid = True
    if not valid:
        raise PipelineError(f"已完成的 {stage} 阶段产物丢失，请使用新输出目录重新运行。")


def reconstruct_colmap(tools, output, attempt):
    command = tools.command("colmap")
    feature_help = capture(command + ["feature_extractor", "-h"], include_stderr=True)
    match_help = capture(command + ["exhaustive_matcher", "-h"], include_stderr=True)
    # New COLMAP moved these options; choose names advertised by the installed executable.
    extraction = "FeatureExtraction" if "--FeatureExtraction.use_gpu" in feature_help else "SiftExtraction"
    matching = "FeatureMatching" if "--FeatureMatching.use_gpu" in match_help else "SiftMatching"
    sparse = attempt / "sparse"
    sparse.mkdir()
    database = attempt / "database.db"
    frames = output / "frames"
    run_logged(command + ["feature_extractor", "--database_path", str(database), "--image_path", str(frames),
               "--ImageReader.single_camera", "1", "--ImageReader.camera_model", PRESET["camera_model"],
               "--SiftExtraction.peak_threshold", str(PRESET["sift_peak_threshold"]),
               "--SiftExtraction.domain_size_pooling", str(PRESET["sift_domain_size_pooling"]),
               "--SiftExtraction.estimate_affine_shape", str(PRESET["sift_estimate_affine_shape"]),
               f"--{extraction}.use_gpu", "0"], attempt / "feature_extractor.log")
    # At 100 frames, exhaustive matching also connects the beginning/end of a walking loop.
    run_logged(command + ["exhaustive_matcher", "--database_path", str(database),
               f"--{matching}.guided_matching", str(PRESET["guided_matching"]),
               f"--{matching}.use_gpu", "0"], attempt / "matcher.log")
    run_logged(command + ["mapper", "--database_path", str(database), "--image_path", str(frames),
               "--output_path", str(sparse)], attempt / "mapper.log")
    model, registered = choose_sparse_model(sparse, len(list(frames.glob("frame_*.jpg"))))
    dataset = attempt / "dataset"
    dataset.mkdir()
    run_logged(command + ["image_undistorter", "--image_path", str(frames), "--input_path", str(model),
               "--output_path", str(dataset), "--output_type", "COLMAP",
               "--max_image_size", str(PRESET["max_image_size"])], attempt / "undistorter.log")
    # Graphdeco reads sparse/0; COLMAP's undistorter writes directly into sparse/.
    sparse_output = dataset / "sparse"
    (sparse_output / "0").mkdir()
    for name in ("cameras", "images", "points3D"):
        source = sparse_output / f"{name}.bin"
        if not source.is_file():
            raise PipelineError(f"缺少去畸变后的相机模型：{source}")
        source.rename(sparse_output / "0" / source.name)
    if len(list((dataset / "images").glob("frame_*.jpg"))) < registered:
        raise PipelineError("去畸变后的图片不完整。")
    dataset.rename(output / "dataset")
    return {"registered_frames": registered, "selected_sparse_model": model.name}


def train_gaussians(tools, output, attempt):
    if trainer_backend(tools) == "brush":
        trained = attempt / "gaussian"
        trained.mkdir()
        padded = prepare_brush_images(output / "dataset")
        command = brush_training_command(tools, output / "dataset", trained)
        write_json(attempt / "training_environment.json", {
            "trainer": "brush", "version": check_training_tools(tools),
            "executable_sha256": file_hash(command[0]), "command": command,
            "small_jpegs_padded": padded})
        run_logged(command, attempt / "training.log", cwd=attempt)
        model = trained / f"model_{PRESET['iterations']}.ply"
        return publish_model(model, output)
    repo, python = tools.trainer()
    environment = training_check(repo, python)
    write_json(attempt / "training_environment.json", {"check": environment, "repo": str(repo),
               "train_py_sha256": file_hash(repo / "train.py")})
    trained = attempt / "gaussian"
    iterations = PRESET["iterations"]
    command = python + [str(repo / "train.py"), "-s", str(output / "dataset"), "-m", str(trained),
              "--iterations", str(iterations), "--position_lr_max_steps", str(iterations),
              "--save_iterations", str(iterations), "--test_iterations", "-1", "--resolution", "1",
              "--data_device", "cpu", "--sh_degree", str(PRESET["sh_degree"]), "--disable_viewer"]
    for name in ("densify_until_iter", "densification_interval", "densify_grad_threshold"):
        command.extend([f"--{name}", str(PRESET[name])])
    run_logged(command, attempt / "training.log", cwd=repo)
    model = trained / "point_cloud" / f"iteration_{iterations}" / "point_cloud.ply"
    return publish_model(model, output)


def publish_model(model, output):
    if not model.is_file():
        raise PipelineError("训练器未生成最终模型，不能把任务标记为完成。请检查 training.log。")
    metrics = gaussian_info(model)
    temporary = output / "model.ply.tmp"
    shutil.copyfile(model, temporary)
    temporary.replace(output / "model.ply")
    return metrics


def doctor(tools):
    report = {"checked_at": utc_now(), "pipeline_python": sys.executable, "checks": {}}
    checks = {
        "ffmpeg": lambda: capture(tools.command("ffmpeg") + ["-version"]).splitlines()[0],
        "ffprobe": lambda: capture(tools.command("ffprobe") + ["-version"]).splitlines()[0],
        "colmap": lambda: capture(tools.command("colmap") + ["-h"], include_stderr=True)[:1500],
        "training": lambda: check_training_tools(tools),
    }
    for name, check in checks.items():
        try:
            detail = check()
            report["checks"][name] = {"ready": True, "detail": detail}
        except (PipelineError, IndexError) as exc:
            report["checks"][name] = {"ready": False, "detail": str(exc)}
    report["ready"] = all(item["ready"] for item in report["checks"].values())
    return report


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="单件家具视频重建：复用 COLMAP 与现成 3DGS 实现")
    parser.add_argument("--tools", type=Path, default=ROOT / "tools.local.json")
    subparsers = parser.add_subparsers(dest="action", required=True)
    doctor_parser = subparsers.add_parser("doctor", help="检查外部工具与训练环境")
    doctor_parser.add_argument("--report", type=Path)
    run_parser = subparsers.add_parser("run", help="运行真实重建流程")
    run_parser.add_argument("--video", required=True)
    run_parser.add_argument("--output", required=True)
    run_parser.add_argument("--stop-after", choices=STAGES, default="train")
    run_parser.add_argument("--resume", action="store_true", help="跳过已完成的阶段；失败阶段重新执行")
    args = parser.parse_args(argv)
    try:
        config = json.loads(args.tools.read_text(encoding="utf-8-sig")) if args.tools.is_file() else {}
        if not isinstance(config, dict):
            raise PipelineError("工具配置必须是 JSON 对象。")
        tools = Tools(config)
        if args.action == "doctor":
            report = doctor(tools)
            print(json.dumps(report, ensure_ascii=False, indent=2))
            if args.report:
                args.report.parent.mkdir(parents=True, exist_ok=True)
                write_json(args.report, report)
            return 0 if report["ready"] else 2
        execute_pipeline(args, tools)
        return 0
    except KeyboardInterrupt:
        print("已中断。完成阶段已保留，可用 --resume 继续。", file=sys.stderr)
        return 130
    except (PipelineError, OSError, ValueError, KeyError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
