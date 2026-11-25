"""Install/run only the isolated probe and collect its own generated files."""
import argparse
import base64
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = "D:/Android SDK/platform-tools/adb.exe"
PACKAGE = "com.furniture.reconstructionprobe"
SERIAL = "537aab3f"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--steps", type=int, choices=[20, 300, 1200], default=20)
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--disable-refinement", action="store_true")
    parser.add_argument("--legacy-subset", action="store_true")
    parser.add_argument("--l1-only", action="store_true")
    args = parser.parse_args()
    args.timeout_seconds = min(args.timeout_seconds,180)
    folder = ROOT / "mobile-reconstruction/results" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    folder.mkdir(parents=True, exist_ok=False)
    build = json.loads((ROOT / "dist/mobile-probe/build-report.json").read_text(encoding="utf-8"))
    apk = Path(build["apk"])
    if hashlib.sha256(apk.read_bytes()).hexdigest() != build["apk_sha256"]:
        raise ValueError("Probe APK hash mismatch")
    def adb(*command, required=True):
        try:
            result = subprocess.run([ADB, "-s", SERIAL, *command], capture_output=True, timeout=120)
        except subprocess.TimeoutExpired:
            if required: raise RuntimeError("Phone command timed out")
            return subprocess.CompletedProcess(command, -1, b"", b"Phone command timed out")
        if required and result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace") or result.stdout.decode("utf-8", "replace"))
        return result
    def app_file(relative, required=False):
        return adb("exec-out", "run-as", PACKAGE, "cat", "files/" + relative, required=required)
    if adb("get-state").stdout.strip() != b"device": raise RuntimeError("Phone is not connected/authorized")
    print("Installing isolated reconstruction probe", flush=True)
    install = adb("install", "-r", str(apk))
    if b"Success" not in install.stdout: raise RuntimeError("APK installation was not successful")
    old_name = app_file("latest-output.txt").stdout.decode("utf-8", "replace").strip()
    # This stops only our test program, never the user's established AR app.
    config = {"disable_refinement":args.disable_refinement,"legacy_subset":args.legacy_subset,"l1_only":args.l1_only}
    encoded = base64.urlsafe_b64encode(json.dumps(config).encode()).decode()
    adb("shell", "am", "start", "-S", "-W", "-n", PACKAGE + "/.MainActivity", "--ei", "probe_steps", str(args.steps), "--es", "probe_config_b64", encoded)
    started = time.monotonic()
    run_name = None
    pid = None
    previous = None
    report = {"apk_sha256": build["apk_sha256"], "serial": SERIAL, "requested_steps": args.steps,
              "result": "running", "scope": "Phone GPU training from desktop-posed images; not end-to-end phone capture"}
    report["probe_configuration"] = config
    try:
        while time.monotonic() - started < args.timeout_seconds:
            current_pid = adb("shell", "pidof", PACKAGE, required=False).stdout.decode().strip()
            if current_pid: pid = current_pid.split()[0]
            if run_name is None:
                latest = app_file("latest-output.txt")
                candidate = latest.stdout.decode("utf-8", "replace").strip()
                if latest.returncode == 0 and candidate != old_name and re.fullmatch(r"probe-\d+-" + str(args.steps), candidate): run_name = candidate
            if run_name:
                result = app_file(run_name + "/progress.json")
                if result.returncode == 0:
                    try: progress = json.loads(result.stdout)
                    except ValueError: progress = None
                    if progress:
                        marker = (progress.get("phase"), progress.get("iteration"))
                        if marker != previous:
                            print(json.dumps(progress, ensure_ascii=False), flush=True);previous = marker
                        (folder / "latest-progress.json").write_bytes(result.stdout)
                        if progress.get("phase") in {"failed", "completed", "timed_out"}:
                            report["native_result"] = progress
                            report["result"] = progress["phase"]
                            break
            if not current_pid and time.monotonic()-started>10:
                raise RuntimeError("Probe process exited unexpectedly")
            time.sleep(3)
        else:
            report["result"] = "timed_out"
            adb("shell", "am", "force-stop", PACKAGE, required=False)
        if run_name:
            for name in ("result.json", "metrics.jsonl"):
                result = app_file(run_name + "/" + name)
                if result.returncode == 0: (folder / name).write_bytes(result.stdout)
            checkpoints = adb("exec-out", "run-as", PACKAGE, "ls", "-1", "files/" + run_name, required=False)
            for name in checkpoints.stdout.decode("utf-8", "replace").splitlines():
                if re.fullmatch(r"(?:generated|after_refinement|invalid)_\d+\.ply", name):
                    data = app_file(run_name + "/" + name, required=True)
                    (folder / name).write_bytes(data.stdout)
        if report["result"] == "completed":
            filename = report["native_result"]["model"]
            if not re.fullmatch(r"generated_\d+\.ply", filename): raise ValueError("Unexpected model filename")
            model = app_file(run_name + "/" + filename, required=True).stdout
            (folder / filename).write_bytes(model)
            import sys
            sys.path.insert(0, str(ROOT))
            import reconstruct
            report["model_validation"] = reconstruct.gaussian_info(folder / filename)
            import numpy as np
            from inspect_result import read_gaussians
            records = read_gaussians(folder / filename)
            if not all(np.isfinite(records[name]).all() for name in records.dtype.names):
                raise ValueError("Phone generated non-finite Gaussian parameters")
            rotations = np.column_stack([records["rot_" + str(i)] for i in range(4)])
            if np.any(np.linalg.norm(rotations, axis=1) < 1e-8):
                raise ValueError("Phone generated invalid Gaussian orientation")
            report["model_validation"]["finite_parameters"] = True
            report["model_sha256"] = hashlib.sha256(model).hexdigest()
            if args.steps > 20:
                directory = run_name + "/eval_" + str(args.steps)
                names = adb("exec-out", "run-as", PACKAGE, "ls", "-1", "files/" + directory, required=False)
                image_dir = folder / "evaluation"
                for name in names.stdout.decode("utf-8", "replace").splitlines():
                    if re.fullmatch(r"frame_\d+\.png", name):
                        image_dir.mkdir(exist_ok=True)
                        data = app_file(directory + "/" + name, required=True)
                        (image_dir / name).write_bytes(data.stdout)
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
    except Exception as error:
        report.update(result="failed", error=str(error))
    finally:
        if not pid and run_name:
            saved_pid = app_file(run_name + "/pid.txt").stdout.decode("utf-8", "replace").strip()
            if re.fullmatch(r"\d+", saved_pid): pid = saved_pid
        if pid and re.fullmatch(r"\d+", pid):
            logs = adb("logcat", "-d", "--pid=" + pid, "-s", "FurnitureProbe", "AndroidRuntime", required=False)
            (folder / "app-log.txt").write_bytes(logs.stdout)
        if (folder / "metrics.jsonl").exists():
            metrics = []
            for line in (folder / "metrics.jsonl").read_text(encoding="utf-8").splitlines():
                try: metrics.append(json.loads(line))
                except ValueError: pass
            if metrics:
                report["metrics_summary"] = {"samples": len(metrics), "peak_pss_mb": max(m["pss_kb"] for m in metrics) / 1024,
                    "max_battery_temperature_c": max(m.get("battery_temperature_c", 0) for m in metrics),
                    "max_android_thermal_status": max(m["thermal_status"] for m in metrics)}
        report["phone_output_folder"] = run_name
        (folder / "phone-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Report:", folder / "phone-report.json", flush=True)
    if report["result"] != "completed": raise SystemExit(1)


if __name__ == "__main__":
    main()
