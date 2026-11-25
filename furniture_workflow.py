"""Local task records and guarded handoffs; no server or automatic segmentation."""
import argparse
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys

import reconstruct as reconstruction

ROOT = Path(__file__).resolve().parent
TASKS = ROOT / "tasks"
PHONE = "537aab3f"


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def save(path, value):
    path = Path(path)
    pending = path.with_suffix(".pending")
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    pending.replace(path)


def task_dir(task_id):
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", task_id):
        raise ValueError("任务编号无效")
    return TASKS / task_id


def load(task_id):
    return read_json(task_dir(task_id) / "task.json")


@contextmanager
def edit(task_id):
    folder = task_dir(task_id)
    with reconstruction.run_lock(folder):
        record = load(task_id)
        try:
            yield record
            record["error"] = None
        except Exception as exc:
            record["error"] = str(exc)
            raise
        finally:
            record["updated_at"] = reconstruction.utc_now()
            save(folder / "task.json", record)


def new_task(video, name, height_cm=None):
    video = Path(video).resolve()
    if not video.is_file() or video.suffix.lower() not in {".mp4", ".mov", ".m4v", ".mkv"}:
        raise ValueError("请选择存在的家具视频（mp4 / mov / m4v / mkv）")
    if not name.strip():
        raise ValueError("请填写家具名称")
    if height_cm is not None and (not math.isfinite(height_cm) or not 5 <= height_cm <= 1000):
        raise ValueError("家具实测高度应为 5～1000 厘米，也可以暂时留空")
    tools = reconstruction.Tools(read_json(ROOT / "tools.local.json"))
    fingerprint = {"video_sha256": reconstruction.file_hash(video), "preset": reconstruction.PRESET,
                   "trainer": reconstruction.trainer_backend(tools)}
    key = hashlib.sha256(json.dumps(fingerprint, sort_keys=True).encode()).hexdigest()[:16]
    task_id = "furniture-" + key
    folder = task_dir(task_id)
    TASKS.mkdir(exist_ok=True)
    if (folder / "task.json").exists():
        return load(task_id)
    folder.mkdir(exist_ok=False)
    # Keep a local input copy so moving the original phone video cannot break resume.
    incoming = folder / ("video" + video.suffix.lower())
    shutil.copyfile(video, incoming)
    if reconstruction.file_hash(incoming) != fingerprint["video_sha256"]:
        raise ValueError("视频复制校验失败，请重新创建任务")
    record = {"schema_version": 1, "id": task_id, "name": name.strip(),
              "created_at": reconstruction.utc_now(), "video": str(incoming),
              "video_sha256": fingerprint["video_sha256"], "height_cm": height_cm,
              "run": str(ROOT / "runs" / task_id), "asset": None, "package": None,
              "phone_test": None, "error": None}
    save(folder / "task.json", record)
    return record


def check_video(record):
    if reconstruction.file_hash(Path(record["video"])) != record["video_sha256"]:
        raise ValueError("任务视频已变化，不能复用旧结果，请创建新任务")


def run_task(task_id):
    with edit(task_id) as record:
        check_video(record)
        tools = reconstruction.Tools(read_json(ROOT / "tools.local.json"))
        output = Path(record["run"])
        args = argparse.Namespace(video=record["video"], output=str(output),
                                  stop_after="train", resume=(output / "run.json").is_file())
        reconstruction.execute_pipeline(args, tools)


def require_reconstruction(record):
    state = read_json(Path(record["run"]) / "run.json")
    if state["status"] != "completed" or state["fingerprint"]["video_sha256"] != record["video_sha256"]:
        raise ValueError("请先完成该视频的重建")
    reconstruction.validate_stage("train", Path(record["run"]))
    return state


def prepare_task(task_id, masks, height_cm):
    """Operator supplies capture-specific masks; never reuse chair masks blindly."""
    from extract_chair import extract
    from prepare_mobile_asset import prepare
    if not math.isfinite(height_cm) or not 5 <= height_cm <= 1000:
        raise ValueError("请输入 5～1000 cm 实测高度")
    with edit(task_id) as record:
        require_reconstruction(record)
        mask_config = read_json(masks)
        if (ROOT / mask_config["source_run"]).resolve() != Path(record["run"]).resolve():
            raise ValueError("轮廓标注属于另一段视频，不能用于当前任务")
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        snapshot = task_dir(task_id) / ("masks-" + stamp + ".json")
        shutil.copyfile(masks, snapshot)
        extracted = extract(snapshot)
        output = task_dir(task_id) / ("asset-" + stamp)
        prepare(Path(record["run"]), height_cm / 100, output, chair=extracted["model"])
        record["height_cm"] = height_cm
        record["candidate_asset"] = str(output)
        print("已提取并校准，请先渲染检查，再登记审核：", output)


def validate_asset(folder):
    folder = Path(folder).resolve()
    calibration = read_json(folder / "calibration.json")
    model_hash = reconstruction.file_hash(folder / "chair.ply")
    if model_hash != calibration["source_sha256"]:
        raise ValueError("模型与尺寸校准不匹配，已停止打包")
    reconstruction.gaussian_info(folder / "chair.ply")
    return {"folder": str(folder), "model_sha256": model_hash,
            "calibration_sha256": reconstruction.file_hash(folder / "calibration.json")}


def approve_asset(task_id, folder, note):
    if not note.strip():
        raise ValueError("需要记录提取与地面/朝向的检查结论")
    with edit(task_id) as record:
        state = require_reconstruction(record)
        asset = validate_asset(folder)
        # Calibration must refer to an extraction from this particular reconstruction.
        source = Path(record["run"]) / "chair.ply"
        candidates = [source] + list((Path(record["run"]) / "extraction").glob("*/chair.ply"))
        if not any(p.is_file() and reconstruction.file_hash(p) == asset["model_sha256"] for p in candidates):
            raise ValueError("该资产不属于本任务的家具提取结果")
        asset.update(review_note=note.strip(), approved_at=reconstruction.utc_now(),
                     reconstruction_sha256=reconstruction.file_hash(Path(record["run"]) / "model.ply"))
        record["asset"] = asset
        record["package"] = None
        record["phone_test"] = None


def checked_asset(record):
    if not record.get("asset"):
        raise ValueError("家具提取和尺寸校准尚未审核，请让我先检查模型")
    asset = record["asset"]
    current = validate_asset(asset["folder"])
    if any(current[k] != asset[k] for k in ("model_sha256", "calibration_sha256")):
        raise ValueError("已审核资产发生变化，需要重新检查")
    if reconstruction.file_hash(Path(record["run"]) / "model.ply") != asset["reconstruction_sha256"]:
        raise ValueError("原始重建已改变，需要重新检查家具提取")
    return asset


def package_task(task_id):
    with edit(task_id) as record:
        asset = checked_asset(record)
        output = task_dir(task_id) / "packages" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        subprocess.run([sys.executable, "-u", str(ROOT / "android-app/build.py"),
                        "--asset-dir", asset["folder"], "--output-dir", str(output)], cwd=ROOT, check=True)
        report = read_json(output / "build-report.json")
        if report["asset"]["source_ply_sha256"] != asset["model_sha256"]:
            raise ValueError("安装包使用了其他模型，已停止交付")
        record["package"] = {"apk": str(output / "ChairPreview.apk"), "sha256": report["apk_sha256"],
                             "created_at": reconstruction.utc_now(), "source": "local_build",
                             "phone_verified": False}
        record["phone_test"] = None


def checked_package(record):
    checked_asset(record)
    package = record.get("package")
    if not package or reconstruction.file_hash(Path(package["apk"])) != package["sha256"]:
        raise ValueError("安装包不存在或发生变化，请重新打包")
    return package


def install_task(task_id, serial=PHONE):
    with edit(task_id) as record:
        package = checked_package(record)
        adb = str(Path("D:/Android SDK/platform-tools/adb.exe"))
        result = subprocess.run([adb, "-s", serial, "get-state"], capture_output=True, text=True)
        if result.returncode or result.stdout.strip() != "device":
            raise ValueError("没有检测到已授权的小米手机，请连接数据线并允许 USB 调试")
        result = subprocess.run([adb, "-s", serial, "install", "-r", package["apk"]],
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        print(result.stdout, result.stderr)
        if result.returncode or "Success" not in result.stdout:
            raise ValueError("手机安装未完成；请查看安装日志。不会自动卸载或清除数据")
        subprocess.run([adb, "-s", serial, "shell", "am", "start", "-W", "-n",
                        "com.furniture.chairdemo/.MainActivity"], check=True)
        record["phone_test"] = {"serial": serial, "installed_at": reconstruction.utc_now(),
                                "apk_sha256": package["sha256"], "result": "awaiting_user_test"}


def status(record):
    """Progress is stage-based. Never invent percentages for a training process."""
    stages = []
    state_path = Path(record["run"]) / "run.json"
    state = read_json(state_path) if state_path.exists() else {}
    done = state.get("completed_stages", [])
    stages.append(("视频已保存", "完成"))
    for key, label in [("frames", "抽帧"), ("colmap", "相机定位"), ("train", "3DGS 重建")]:
        value = "完成" if key in done else "等待"
        if key == state.get("stage") and state.get("status") in {"running", "failed", "interrupted"}:
            value = {"running": "处理中", "failed": "失败，可重试", "interrupted": "已中断，可继续"}[state["status"]]
        stages.append((label, value))
    stages.append(("家具提取与尺寸检查", "已审核" if record.get("asset") else "待我处理"))
    stages.append(("手机安装包", "已生成" if record.get("package") else "等待"))
    phone = record.get("phone_test")
    stages.append(("手机实测", "已确认（有边缘残留）" if phone and phone["result"] == "confirmed_with_limits"
                   else "已安装，待实测" if phone else "等待"))
    return stages


def adopt_demo():
    """Register existing user-approved artifacts without rebuilding or installing."""
    run = ROOT / "runs/furniture-470cc7e52c525dbe"
    state = read_json(run / "run.json")
    record = new_task(state["video"], "实拍椅子 · 已验证样例")
    task_id = record["id"]
    if task_id != run.name:
        raise ValueError("样例的固定参数已变化，不能关联旧实验")
    if record.get("package"):
        return record
    approve_asset(task_id, ROOT / "mobile-assets/chair-1m",
                  "已核对四个原拍摄视角；用户确认手机尺寸正常。脚边晕影和少量遮挡马赛克保留为已知限制。")
    baseline = ROOT / "dist/demo-baseline-v0.3.1"
    report = read_json(baseline / "build-report.json")
    with edit(task_id) as record:
        if report["asset"]["source_ply_sha256"] != record["asset"]["model_sha256"]:
            raise ValueError("演示基线不是该家具")
        apk = baseline / "ChairPreview.apk"
        if reconstruction.file_hash(apk) != report["apk_sha256"]:
            raise ValueError("演示安装包校验失败")
        record["package"] = {"apk": str(apk), "sha256": report["apk_sha256"],
                             "source": "user_tested_v0.3.1", "phone_verified": True}
        record["phone_test"] = {"serial": PHONE, "result": "confirmed_with_limits",
                                "apk_sha256": report["apk_sha256"],
                                "note": "用户确认尺寸正常、深度图明显改善；遮挡略改善，开关差别小，仍有少量马赛克。"}
    return load(task_id)


def main():
    parser = argparse.ArgumentParser(description="家具视频到手机的本地流程")
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("demo")
    new = commands.add_parser("new")
    new.add_argument("--video", required=True)
    new.add_argument("--name", required=True)
    new.add_argument("--height-cm", type=float)
    for action in ("status", "reconstruct", "package", "install", "prepare", "approve"):
        command = commands.add_parser(action)
        command.add_argument("id")
        if action == "prepare":
            command.add_argument("--masks", type=Path, required=True)
            command.add_argument("--height-cm", type=float, required=True)
        if action == "approve":
            command.add_argument("--asset-dir", type=Path, required=True)
            command.add_argument("--note", required=True)
    args = parser.parse_args()
    if args.action == "demo":
        record = adopt_demo()
    elif args.action == "new":
        record = new_task(args.video, args.name, args.height_cm)
    else:
        if args.action == "reconstruct": run_task(args.id)
        elif args.action == "prepare": prepare_task(args.id, args.masks, args.height_cm)
        elif args.action == "approve": approve_asset(args.id, args.asset_dir, args.note)
        elif args.action == "package": package_task(args.id)
        elif args.action == "install": install_task(args.id)
        record = load(args.id)
    print(json.dumps({"task": record, "stages": status(record)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"): sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (OSError, ValueError, KeyError, reconstruction.PipelineError, subprocess.CalledProcessError) as exc:
        print("流程未完成：" + str(exc), file=sys.stderr)
        raise SystemExit(1)
