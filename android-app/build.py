"""Build the local Java/OpenGL ES prototype with existing Android SDK tools.

No Gradle, Unity, remote build server or NDK required. External ARCore AAR is
downloaded separately from Google's Maven and pinned by the build report hash.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

import numpy as np

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))
from inspect_result import read_gaussians
from extract_chair import quaternion_matrix
from asset_geometry import calibrate


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def pack_asset(destination, geometry=True, folder=None):
    folder = Path(folder) if folder is not None else ROOT.parent / "mobile-assets/chair-1m"
    report = json.loads((folder / "calibration.json").read_text())
    records = read_gaussians(folder / "chair.ply")
    if sha(folder / "chair.ply") != report["source_sha256"]:
        raise ValueError("Calibration/model mismatch")
    rotation = np.array(report["rotation_rows"])
    scale = report["uniform_scale"]
    translation = np.array(report["translation_m"])
    raw = np.column_stack([records[k] for k in ("x", "y", "z")])
    xyz = raw @ rotation.T * scale + translation
    values = np.zeros((len(records), 37), dtype="<f4")
    values[:, :3] = xyz
    errors = []
    for i, record in enumerate(records):
        orient = quaternion_matrix([record[f"rot_{j}"] for j in range(4)])
        radii = np.exp([record[f"scale_{j}"] for j in range(3)])
        axes = scale * rotation @ orient @ np.diag(radii)
        cov = axes @ axes.T
        if np.linalg.eigvalsh(cov).min() <= 0:
            raise ValueError("Non-positive covariance")
        values[i, 3:6] = np.diag(cov)
        values[i, 6:9] = cov[0, 1], cov[0, 2], cov[1, 2]
        errors.append(abs(np.trace(cov) - scale**2 * np.sum(radii**2)))
    values[:, 9:12] = np.column_stack([records[f"f_dc_{c}"] for c in range(3)])
    values[:, 12] = 1 / (1 + np.exp(-records["opacity"].astype(float)))
    for j in range(8):
        values[:, 13+j*3:16+j*3] = np.column_stack([records[f"f_rest_{c*8+j}"] for c in range(3)])
    if not np.isfinite(values).all():
        raise ValueError("Non-finite packed Gaussian")
    inverse_rotation=rotation.T
    dimensions=None
    if geometry:
        values,inverse_rotation,dimensions=calibrate(values,inverse_rotation,destination.parent.parent/'geometry')
        dimensions['asset_id'] = report['source_sha256']
        (destination.parent/'dimensions.json').write_text(json.dumps(dimensions),encoding='utf-8')
    # mat3 uniforms are column-major. SH directions remain in source coordinates.
    with destination.open("wb") as stream:
        stream.write(struct.pack("<II", 0x31534746, len(records)))
        stream.write(inverse_rotation.astype("<f4").tobytes(order="F"))
        stream.write(values.tobytes())
    if not geometry:np.testing.assert_allclose(xyz[:, 1],values[:,1],atol=1e-6)
    return {"gaussians": len(records), "source_ply_sha256": report["source_sha256"],
            "packed_sha256": sha(destination), "height_top_percentile_m": float(np.percentile(values[:, 1], 99.8)),
            "max_covariance_trace_error": float(max(errors)), "sh_degree": 2,"geometry":dimensions}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, default=Path("D:/Android SDK"))
    parser.add_argument("--java", type=Path, default=Path("D:/Android Studio/jbr"))
    parser.add_argument("--asset-dir", type=Path, default=ROOT.parent / "mobile-assets/chair-1m")
    parser.add_argument("--output-dir", type=Path, default=ROOT.parent / "dist")
    parser.add_argument("--with-training", action="store_true", help="Bundle the verified phone backend and reference capture")
    parser.add_argument("--native-library", type=Path, help="Explicit experimental native backend; hash recorded in report")
    args = parser.parse_args()
    sdk, java = args.sdk, args.java
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-37.0/android.jar"
    aar = ROOT.parent / "tools/arcore-1.48.0"
    zxing = ROOT.parent / 'tools/zxing/core-3.5.3.jar'
    if not zxing.is_file() or sha(zxing) != '8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82':
        raise ValueError('Missing or changed pinned tools/zxing/core-3.5.3.jar')
    if not (aar / "classes.jar").exists():
        raise ValueError("Extract tools/downloads/arcore-1.48.0.aar to tools/arcore-1.48.0 first")
    work = ROOT / "build" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    for folder in [work, work / "gen", work / "classes", work / "dex", work / "assets"]:
        folder.mkdir(parents=True, exist_ok=True)
    log = (work / "build.log").open("w", encoding="utf-8")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java)
    env["PATH"] = str(java / "bin") + os.pathsep + env.get("PATH", "")
    env["TMP"] = env["TEMP"] = str(work)
    def run(*command):
        command = [str(x) for x in command]
        print("Running", Path(command[0]).name, flush=True)
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        log.write(" ".join(command) + "\n" + result.stdout + "\n");log.flush()
        if result.returncode:
            print(result.stdout)
            raise RuntimeError(f"Build failed, see {work / 'build.log'}")
    for source in (ROOT / "assets").iterdir():
        shutil.copyfile(source, work / "assets" / source.name)
    asset = pack_asset(work / "assets/chair.bin", folder=args.asset_dir)
    manifest = ROOT / "AndroidManifest.xml"
    training = None
    if args.with_training:
        from prepare_capture_rules import prepare
        if asset['source_ply_sha256'] != 'e70990bccee261c0ac576dff5ab6ce691610016f766cde159378446765d52915':
            raise ValueError('Reference phone generation requires the matching chair calibration')
        backend_manifest = ROOT.parent/'mobile-reconstruction/verified-backend.json'
        verified = json.loads((backend_manifest if backend_manifest.exists() else ROOT.parent/'dist/mobile-probe/stable-fixed-v04/build-report.json').read_text(encoding='utf-8'))
        native = args.native_library or ROOT.parent/'tools/mobile-build/target/aarch64-linux-android/release/libfurniture_probe.so'
        if not args.native_library and sha(native) != verified['native_sha256']: raise ValueError('Phone backend differs from the verified engine')
        preflight = json.loads((ROOT.parent/'mobile-reconstruction/preflight.json').read_text(encoding='utf-8'))
        fixture = json.loads(Path(preflight['fixture_manifest']).read_text(encoding='utf-8'))
        if sha(fixture['archive']['path']) != fixture['archive']['sha256']: raise ValueError('Capture fixture changed')
        shutil.copyfile(fixture['archive']['path'],work/'assets/posed-chair-capture.zip')
        rules = prepare(work/'assets/capture-rules')
        training = {'fixture_sha256':fixture['archive']['sha256'],'capture_id':rules['capture_id'],'native_sha256':sha(native),'mode':'configurable experimental' if args.native_library else verified.get('mode','fixed count; no refinement'),'budget_seconds':600,'new_capture_preset':'balanced-v0121'}
        (work/'assets/training-enabled.json').write_text(json.dumps(training),encoding='utf-8')
        licenses=work/'assets/third-party';licenses.mkdir()
        shutil.copyfile(ROOT.parent/'third_party/brush-v0.3.0/LICENSE',licenses/'BRUSH-LICENSE.txt')
        namespace='http://schemas.android.com/apk/res/android';ET.register_namespace('android',namespace)
        tree=ET.parse(manifest);application=tree.getroot().find('application');application.set('{'+namespace+'}debuggable','true')
        ET.SubElement(application,'activity',{'{'+namespace+'}name':'com.furniture.reconstructionprobe.MainActivity','{'+namespace+'}exported':'false','{'+namespace+'}process':':reconstruction','{'+namespace+'}screenOrientation':'portrait','{'+namespace+'}configChanges':'orientation|screenSize|keyboardHidden'})
        manifest=work/'AndroidManifest.xml';tree.write(manifest,encoding='utf-8',xml_declaration=True)
    licenses=work/'assets/third-party';licenses.mkdir(exist_ok=True)
    shutil.copyfile(ROOT.parent/'tools/zxing/LICENSE',licenses/'ZXING-LICENSE.txt')
    run(tools / "aapt2.exe", "compile", "--dir", aar / "res", "-o", work / "resources.zip")
    run(tools / "aapt2.exe", "link", "-o", work / "base.apk", "-I", android,
        "--manifest", manifest, "--java", work / "gen",
        "--extra-packages", "com.google.ar.core", "-A", work / "assets", "--auto-add-overlay", work / "resources.zip")
    sources = list((ROOT / "src").rglob("*.java")) + list((work / "gen").rglob("*.java"))
    if args.with_training: sources += list((ROOT.parent/'mobile-reconstruction/android/src').rglob('*.java'))
    licenses=work/'assets/third-party';licenses.mkdir(exist_ok=True)
    shutil.copyfile(ROOT.parent/'tools/zxing/LICENSE',licenses/'ZXING-LICENSE.txt')
    run(java / "bin/javac.exe", "--release", "8", "-encoding", "UTF-8", "-classpath", os.pathsep.join(map(str,[android,aar/'classes.jar',zxing])), "-d", work / "classes", *sources)
    with zipfile.ZipFile(work / "app.jar", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for source in (work / "classes").rglob("*.class"):
            archive.write(source, source.relative_to(work / "classes").as_posix())
    run(java / "bin/java.exe", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "24", "--lib", android, "--output", work / "dex", work / "app.jar", aar / "classes.jar", zxing)
    shutil.copyfile(work / "base.apk", work / "unsigned.apk")
    with zipfile.ZipFile(work / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for source in (work / "dex").glob("*.dex"):
            archive.write(source, source.name)
        for source in (aar / "jni").rglob("*.so"):
            archive.write(source, "lib/"+source.relative_to(aar / "jni").as_posix())
        if args.with_training: archive.write(native,'lib/arm64-v8a/libfurniture_probe.so')
    run(tools / "zipalign.exe", "-P", "16", "-f", "4", work / "unsigned.apk", work / "aligned.apk")
    key = ROOT.parent / "tools/android-signing/preview.keystore"
    key.parent.mkdir(exist_ok=True)
    if not key.exists():
        run(java / "bin/keytool.exe", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android", "-alias", "preview", "-dname", "CN=Local Furniture Preview", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650")
    run(java / "bin/java.exe", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-pass", "pass:android", "--out", work / "ChairPreview.apk", work / "aligned.apk")
    run(java / "bin/java.exe", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", work / "ChairPreview.apk")
    run(tools / "zipalign.exe", "-c", "-P", "16", "4", work / "ChairPreview.apk")
    target = args.output_dir.resolve()
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(work / "ChairPreview.apk", target / "ChairPreview.apk")
    report = {"apk": str(target / "ChairPreview.apk"), "apk_sha256": sha(target / "ChairPreview.apk"),
              "apk_size_bytes": (target / "ChairPreview.apk").stat().st_size, "asset": asset,
              "arcore_aar_sha256": sha(ROOT.parent / "tools/downloads/arcore-1.48.0.aar"),
              "toolchain": {"sdk": str(sdk), "java": str(java), "build_tools": "36.0.0"},
              "build_log": str(work / "build.log"), "device_tested": False}
    report["asset_directory"] = str(args.asset_dir.resolve())
    report['phone_training'] = training
    (target / "build-report.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
