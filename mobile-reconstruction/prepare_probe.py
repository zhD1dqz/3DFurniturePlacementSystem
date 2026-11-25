"""Prepare unmodified posed images for an actual Android training probe.

The archive intentionally contains no pre-trained Gaussian PLY. It tests only
the optimization backend, not phone capture, pose estimation or segmentation.
"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "runs/furniture-470cc7e52c525dbe"


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    source = RUN / "dataset"
    state = json.loads((RUN / "run.json").read_text(encoding="utf-8"))
    if state["status"] != "completed":
        raise ValueError("Reference reconstruction is incomplete")
    files = sorted((source / "images").glob("*.jpg"))
    files += [source / "sparse/0" / (name + ".bin") for name in ("cameras", "images", "points3D")]
    if len(files) != 103 or not all(path.is_file() for path in files):
        raise ValueError("Expected 100 images and the three original COLMAP files")
    with (source / "sparse/0/images.bin").open("rb") as stream:
        registered = struct.unpack("<Q", stream.read(8))[0]
    if registered != 100:
        raise ValueError("Reference camera count changed")
    output = ROOT / "mobile-reconstruction/probes" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"purpose": "Android Gaussian optimization backend probe only",
                "source_video_sha256": state["fingerprint"]["video_sha256"],
                "source_run": str(RUN), "images": 100, "registered_cameras": registered,
                "contains_pretrained_gaussian_model": False,
                "pose_origin": "Previously computed on the desktop using COLMAP",
                "scope_excludes": ["Phone capture", "Phone-only pose recovery", "Furniture segmentation", "Complete offline app"],
                "files": []}
    archive_path = output / "posed-chair-capture.zip"
    with zipfile.ZipFile(archive_path, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            name = path.relative_to(source).as_posix()
            before = sha(path)
            archive.write(path, name)
            if sha(path) != before:
                raise ValueError("Source changed while packing: " + name)
            manifest["files"].append({"path": name, "bytes": path.stat().st_size, "sha256": before})
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None:
            raise ValueError("Archive integrity failed")
        if set(archive.namelist()) != {entry["path"] for entry in manifest["files"]}:
            raise ValueError("Archive entries do not match manifest")
        for entry in manifest["files"]:
            if hashlib.sha256(archive.read(entry["path"])).hexdigest() != entry["sha256"]:
                raise ValueError("Archived file differs: " + entry["path"])
    manifest["archive"] = {"path": str(archive_path), "bytes": archive_path.stat().st_size,
                           "sha256": sha(archive_path)}
    (output / "fixture-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    paths = {"rust_on_path": shutil.which("rustc"), "cargo_on_path": shutil.which("cargo"),
             "rustup_on_path": shutil.which("rustup"),
             "default_user_rustc": str(Path.home() / ".cargo/bin/rustc.exe"),
             "android_sdk": "D:/Android SDK", "android_ndk": "D:/Android SDK/ndk",
             "java": "D:/Android Studio/jbr/bin/java.exe"}
    readiness = {"rust_detected": bool(paths["rust_on_path"] or Path(paths["default_user_rustc"]).is_file()),
                 "android_sdk_present": Path(paths["android_sdk"]).is_dir(),
                 "android_ndk_present": any(Path(paths["android_ndk"]).glob("*/source.properties")),
                 "java_present": Path(paths["java"]).is_file()}
    report = {"stage": "Training probe inputs prepared; Android training not yet run",
              "fixture_manifest": str(output / "fixture-manifest.json"), "tool_paths": paths,
              "readiness": readiness, "android_training_verified": False,
              "gpu_driver_compatibility_verified": False, "phone_end_to_end_verified": False,
              "next": "Prepare Android build tools, compile isolated ARM64 probe, run and export on Xiaomi 17 Pro"}
    (ROOT / "mobile-reconstruction/preflight.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"archive": manifest["archive"], "readiness": readiness,
                      "android_training_verified": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
