"""Build separate Android instrumentation; never included in the user APK."""
import json
import argparse
import os
from pathlib import Path
import subprocess
import zipfile
import sys

root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--report',type=Path,default=root.parent/'dist/build-report.json')
parser.add_argument('--output',type=Path,default=root.parent/'dist/ChairTests.apk')
args=parser.parse_args()
report=json.loads(args.report.read_text())
work=Path(report['build_log']).parent/'test'
work.mkdir(exist_ok=True)
for name in ('classes','dex'):(work/name).mkdir(exist_ok=True)
sys.path.insert(0,str(root))
from prepare_library_test_fixture import prepare
(work/'assets').mkdir(exist_ok=True)
if (work.parent/'assets/capture-rules').is_dir(): prepare(work/'assets',work.parent/'assets/capture-rules')
java=Path(report['toolchain']['java'])/'bin'
sdk=Path(report['toolchain']['sdk']);tools=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-37.0/android.jar'
env=os.environ.copy();env['TEMP']=env['TMP']=str(work)
def run(*command):subprocess.run([str(x) for x in command],env=env,check=True)
run(tools/'aapt2.exe','link','-I',android,'--manifest',root/'tests/AndroidManifest.xml','-A',work/'assets','-o',work/'base.apk')
run(java/'javac.exe','--release','8','-encoding','UTF-8','-classpath',os.pathsep.join(map(str,[android,work.parent/'app.jar',root.parent/'tools/arcore-1.48.0/classes.jar',root.parent/'tools/zxing/core-3.5.3.jar'])),'-d',work/'classes',*list((root/'tests').glob('*.java')))
with zipfile.ZipFile(work/'test.jar','w') as archive:
 for path in (work/'classes').rglob('*.class'):archive.write(path,path.relative_to(work/'classes').as_posix())
run(java/'java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',android,'--classpath',work.parent/'app.jar','--output',work/'dex',work/'test.jar')
with zipfile.ZipFile(work/'base.apk','a') as archive:archive.write(work/'dex/classes.dex','classes.dex')
run(tools/'zipalign.exe','-f','4',work/'base.apk',work/'aligned.apk')
run(java/'java.exe','-jar',tools/'lib/apksigner.jar','sign','--ks',root.parent/'tools/android-signing/preview.keystore','--ks-pass','pass:android','--out',args.output,work/'aligned.apk')
print('Separate test APK ready')
