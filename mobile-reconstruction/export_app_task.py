"""Read only this project's own Android application data for reconstruction QA."""
import argparse
import io
import re
import subprocess
import tarfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('remote', help='App-relative folder, e.g. captures/scan-... or probe-...')
parser.add_argument('output', type=Path)
args = parser.parse_args()
if not re.fullmatch(r'(captures/scan-\d+-[a-f0-9]{8}|probe-\d+-1200)', args.remote):
    raise ValueError('Only capture and training task folders may be exported')
command = ['D:/Android SDK/platform-tools/adb.exe', '-s', '537aab3f', 'exec-out',
           'run-as', 'com.furniture.chairdemo', 'tar', '-C', 'files', '-cf', '-', args.remote]
result = subprocess.run(command, capture_output=True, check=True)
args.output.mkdir(parents=True, exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(result.stdout)) as archive:
    archive.extractall(args.output, filter='data')
print(f'Exported {args.remote}: {len(result.stdout)} bytes')
