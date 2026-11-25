"""Read-only deployment check. Does not start training or print pairing secrets."""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools/server-python"))


def main():
    failed = []
    def check(name, ok, detail=''):
        print(f"{'OK' if ok else 'FAIL'} {name} {detail}")
        if not ok:
            failed.append(name)
    check('Python', sys.version_info >= (3, 11), sys.version.split()[0])
    for module, package in [('numpy', 'numpy'), ('scipy.spatial.transform', 'scipy'), ('PIL.Image', 'Pillow'), ('qrcode', 'qrcode')]:
        try:
            __import__(module)
            check(package, True, importlib.metadata.version(package))
        except ImportError as error:
            check(package, False, str(error))
    for name in ['desktop-server/server.py', 'desktop-server/pipeline.py',
                 'android-app/validate_capture.py', 'inspect_result.py', 'reconstruct.py']:
        check(name, (ROOT / name).is_file())
    for name, args in [('colmap/bin/colmap.exe', ['-h']), ('brush/brush_app.exe', ['--help'])]:
        path = ROOT / 'tools' / name
        try:
            result = subprocess.run([str(path), *args], capture_output=True, timeout=30,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            check(name, result.returncode == 0, f'exit={result.returncode}')
        except (OSError, subprocess.TimeoutExpired) as error:
            check(name, False, str(error))
    manifest = ROOT / 'package-manifest.json'
    if manifest.exists():
        data = json.loads(manifest.read_text(encoding='utf-8'))
        bad = []
        for row in data['files']:
            path = ROOT / row['path']
            if not path.is_file():
                bad.append(row['path'])
                continue
            with path.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != row['sha256']:
                    bad.append(row['path'])
        check('Package SHA256', not bad, ', '.join(bad))
    print('Tool checks do not verify GPU training, phone AR, or network access.')
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
