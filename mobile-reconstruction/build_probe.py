"""Build an isolated Android/JNI training probe with locally scoped toolchains."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MOBILE = ROOT / "mobile-reconstruction"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-native", action="store_true")
    args = parser.parse_args()
    source = ROOT / "third_party/brush-v0.3.0"
    toolchain = ROOT / "tools/mobile-build"
    sdk = Path("D:/Android SDK")
    java = Path("D:/Android Studio/jbr")
    ndk = toolchain / "android-ndk-r30/toolchains/llvm/prebuilt/windows-x86_64/bin"
    build_tools = sdk / "build-tools/36.0.0"
    android_jar = sdk / "platforms/android-37.0/android.jar"
    work = MOBILE / "build" / datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    for directory in (work, work / "classes", work / "dex", work / "assets"):
        directory.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(CARGO_HOME=str(toolchain / "cargo"), RUSTUP_HOME=str(toolchain / "rustup"),
               CARGO_TARGET_DIR=str(toolchain / "target"), JAVA_HOME=str(java),
               TEMP=str(work), TMP=str(work), NO_PROXY="*", CARGO_NET_GIT_FETCH_WITH_CLI="true",
               CARGO_HTTP_TIMEOUT="120", CARGO_HTTP_MULTIPLEXING="false")
    env["PATH"] = os.pathsep.join(map(str, (toolchain / "cargo/bin", Path("D:/MinGW/MinGW/bin"), ndk, java / "bin", Path("D:/Git/cmd")))) + os.pathsep + env.get("PATH", "")
    rust_bin = toolchain / "rustup/toolchains/1.90.0-x86_64-pc-windows-gnu/bin"
    env["RUSTC"] = str(rust_bin / "rustc.exe")
    env["RUSTDOC"] = str(rust_bin / "rustdoc.exe")
    env["PATH"] = str(rust_bin) + os.pathsep + env["PATH"]
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(ndk / "clang.exe")
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"] = "-C link-arg=--target=aarch64-linux-android29 -C link-arg=-Wl,-z,max-page-size=16384"
    env["CC_aarch64_linux_android"] = str(ndk / "clang.exe")
    env["CXX_aarch64_linux_android"] = str(ndk / "clang++.exe")
    env["AR_aarch64_linux_android"] = str(ndk / "llvm-ar.exe")
    env["CFLAGS_aarch64_linux_android"] = "--target=aarch64-linux-android29"
    env["CXXFLAGS_aarch64_linux_android"] = "--target=aarch64-linux-android29"
    log_path = work / "build.log"
    def run(*command, cwd=ROOT):
        print("Running", Path(command[0]).name, flush=True)
        with log_path.open("a", encoding="utf-8") as log:
            log.write(" ".join(map(str, command)) + "\n");log.flush()
            result = subprocess.run(list(map(str, command)), cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            print(log_path.read_text(encoding="utf-8", errors="replace")[-7000:])
            raise RuntimeError(f"Command failed; see {log_path}")
    probe = source / "examples/furniture-probe"
    shutil.copytree(MOBILE / "native", probe, dirs_exist_ok=True)
    # Reproducible Android port: keep compute modules, omit desktop-only UI and
    # Rerun telemetry. The optimizer epsilon patch below is an explicit mobile
    # numerical-stability experiment; Gaussian rendering kernels stay unchanged.
    patch_records = []
    def patch(relative, old, new):
        path = source / relative
        text = path.read_text(encoding="utf-8")
        if new in text:
            pass
        elif old in text:
            text = text.replace(old, new)
            path.write_text(text, encoding="utf-8")
        else:
            raise ValueError("Unexpected upstream file while applying Android port: " + relative)
        patch_records.append({"file":relative,"sha256":sha(path)})
    patch("Cargo.toml", 'members = ["crates/*", "examples/*"]', 'members = ["examples/furniture-probe"]')
    patch("Cargo.toml", 'default-members = ["crates/brush-app"]', 'default-members = ["examples/furniture-probe"]')
    patch("crates/rrfd/Cargo.toml", 'cfg(target_family = "android")', 'cfg(target_os = "android")')
    patch("crates/rrfd/Cargo.toml", 'cfg(not(target_family = "wasm"))', 'cfg(all(not(target_family = "wasm"), not(target_os = "android")))')
    patch("crates/rrfd/Cargo.toml", '[target.\'cfg(target_os = "android")\'.dependencies]\njni =', '[target.\'cfg(target_os = "android")\'.dependencies]\ntokio = { workspace = true, features = ["fs", "sync", "io-util"] }\njni =')
    patch("crates/rrfd/src/lib.rs", '\n}\n\n/// Saves data to a file', '\n    #[cfg(target_os = "android")]\n    { Err(PickFileError::NoDirectorySelected) }\n}\n\n/// Saves data to a file')
    patch("crates/brush-process/Cargo.toml", 'cfg(not(target_family = "wasm"))', 'cfg(all(not(target_family = "wasm"), not(target_os = "android")))')
    patch("crates/brush-process/src/visualize_tools.rs", '#[cfg(not(target_family = "wasm"))]', '#[cfg(all(not(target_family = "wasm"), not(target_os = "android")))]')
    patch("crates/brush-process/src/visualize_tools.rs", '#[cfg(target_family = "wasm")]', '#[cfg(any(target_family = "wasm", target_os = "android"))]')
    patch("crates/brush-train/src/train.rs", 'AdamScaledConfig::new().with_epsilon(1e-15).init()', 'AdamScaledConfig::new().with_epsilon(1e-8).init()')
    # Android-only workaround verified separately on the actual device. Keep
    # coefficient learning-rate scaling while restarting moments on topology changes.
    patch("crates/brush-train/src/train.rs", '    state.momentum = state.momentum.map(|mut moment| {\n        moment.moment_1 = map_opt(moment.moment_1);\n        moment.moment_2 = map_opt(moment.moment_2);\n        moment\n    });', '    // Android experiment: isolate concatenated optimizer-state handling after\n    // topology changes. Preserve per-coefficient LR scaling, restart moments.\n    // This is a diagnostic workaround, not a verified upstream bug fix.\n    #[cfg(target_os = "android")]\n    { state.momentum = None; }\n    #[cfg(not(target_os = "android"))]\n    { state.momentum = state.momentum.map(|mut moment| {\n        moment.moment_1 = map_opt(moment.moment_1);\n        moment.moment_2 = map_opt(moment.moment_2);\n        moment\n    }); }')
    (work / "android-port-patches.json").write_text(json.dumps(patch_records,indent=2),encoding="utf-8")
    if not args.skip_native:
        run(rust_bin / "cargo.exe", "build", "--release", "--target", "aarch64-linux-android", "-p", "furniture-probe", "-j", "6", cwd=source)
    library = toolchain / "target/aarch64-linux-android/release/libfurniture_probe.so"
    if not library.is_file(): raise FileNotFoundError(library)
    preflight = json.loads((MOBILE / "preflight.json").read_text(encoding="utf-8"))
    fixture = json.loads(Path(preflight["fixture_manifest"]).read_text(encoding="utf-8"))
    archive = Path(fixture["archive"]["path"])
    if sha(archive) != fixture["archive"]["sha256"]: raise ValueError("Fixture hash mismatch")
    shutil.copyfile(archive, work / "assets/posed-chair-capture.zip")
    run(build_tools / "aapt2.exe", "link", "-o", work / "base.apk", "-I", android_jar,
        "--manifest", MOBILE / "android/AndroidManifest.xml", "-A", work / "assets")
    studio = ROOT / "android-app/src/com/furniture/chairdemo"
    shared_ui = [studio / name for name in ("Ui.java", "StudioIcon.java", "StudioDialog.java", "WindowLayout.java")]
    run(java / "bin/javac.exe", "--release", "8", "-encoding", "UTF-8", "-classpath", android_jar,
        "-d", work / "classes", *list((MOBILE / "android/src").rglob("*.java")), *shared_ui)
    with zipfile.ZipFile(work / "app.jar", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in (work / "classes").rglob("*.class"): archive.write(path, path.relative_to(work / "classes").as_posix())
    run(java / "bin/java.exe", "-cp", build_tools / "lib/d8.jar", "com.android.tools.r8.D8",
        "--min-api", "29", "--lib", android_jar, "--output", work / "dex", work / "app.jar")
    shutil.copyfile(work / "base.apk", work / "unsigned.apk")
    with zipfile.ZipFile(work / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(work / "dex/classes.dex", "classes.dex")
        archive.write(library, "lib/arm64-v8a/libfurniture_probe.so")
    run(build_tools / "zipalign.exe", "-P", "16", "-f", "4", work / "unsigned.apk", work / "aligned.apk")
    run(java / "bin/java.exe", "-jar", build_tools / "lib/apksigner.jar", "sign", "--ks",
        ROOT / "tools/android-signing/preview.keystore", "--ks-pass", "pass:android", "--out", work / "FurnitureProbe.apk", work / "aligned.apk")
    run(java / "bin/java.exe", "-jar", build_tools / "lib/apksigner.jar", "verify", "--verbose", work / "FurnitureProbe.apk")
    run(build_tools / "zipalign.exe", "-c", "-P", "16", "4", work / "FurnitureProbe.apk")
    output = ROOT / "dist/mobile-probe"
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(work / "FurnitureProbe.apk", output / "FurnitureProbe.apk")
    report = {"apk": str(output / "FurnitureProbe.apk"), "apk_sha256": sha(output / "FurnitureProbe.apk"),
              "native_sha256": sha(library), "native_bytes": library.stat().st_size,
              "build_log": str(log_path), "input_fixture_sha256": fixture["archive"]["sha256"],
              "package": "com.furniture.reconstructionprobe", "real_phone_training_verified": False}
    report["brush_source_commit"] = "3edecbb2fe79d3e2c87eeab85b15e0b1dd10d486"
    report["dependency_lock_sha256"] = sha(source / "Cargo.lock")
    report["android_port_patches"] = str(work / "android-port-patches.json")
    (output / "build-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
