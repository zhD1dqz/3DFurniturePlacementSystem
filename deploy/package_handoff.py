"""Create a runtime handoff using an explicit allowlist; never copy private data."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / 'dist/mobile-functions-v014/ChairPreview.apk'
EXPECTED_APK = 'c82f1585795a3e6b076b816944425d3b1df1570e9011b73e2d3ec0b294991256'


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    if digest(APK) != EXPECTED_APK:
        raise ValueError('APK differs from pinned v0.14.0; review release before packaging')
    parent = ROOT / 'dist/组员部署包'
    target = parent / ('家居重建-部署包-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    target.mkdir(parents=True)

    def copy(source, dest=None):
        path = ROOT / source
        output = target / (dest or source)
        output.parent.mkdir(parents=True, exist_ok=True)
        if path.is_dir():
            shutil.copytree(path, output, ignore=shutil.ignore_patterns('__pycache__', '*.pyc', '.git'))
        else:
            shutil.copy2(path, output)

    for file in ['README.md', 'requirements-server.txt', '启动电脑重建服务.cmd',
                 'inspect_result.py', 'reconstruct.py', 'android-app/validate_capture.py',
                 'deploy/check_deployment.py', 'desktop-server/server.py',
                 'desktop-server/pipeline.py', 'desktop-server/test_server.py',
                 'mobile-reconstruction/verified-backend.json', 'docs/功能版-v014.md']:
        copy(file)
    for folder in ['tools/colmap', 'tools/brush', 'tools/zxing', 'tools/server-python', 'android-app/src',
                   'mobile-reconstruction/native']:
        copy(folder)
    copy('android-app/AndroidManifest.xml')
    copy('dist/mobile-functions-v014/verification.json', 'verification.json')
    copy('dist/mobile-functions-v014/ChairPreview.apk', 'app/家居重建-v0.14.0.apk')
    (target/'THIRD_PARTY.md').write_text('''# 第三方组件

本包保留 tools/brush 下的 LICENSE、README、CHANGELOG 和 COLMAP 配套文件。

- COLMAP 4.2.1 Windows nocuda：BSD-3-Clause，https://github.com/colmap/colmap/tree/4.2.1
  官方发布包下载时记录 SHA256：c493d88cb4a43f21afba42cb55bf786e5434a0420e73db0ba1454c5d8b3686ae。
  此值是原始 ZIP 的校验值，解压文件校验见 package-manifest.json。
- Brush v0.3.0：Apache-2.0，https://github.com/ArthurBrussee/brush/tree/v0.3.0
  Windows 原始 ZIP SHA256：b68e3e9cf052d51bf3ee30776fa5a364de7f2ba13b58443128ff797bb7bcfcd6。
- APK 内集成 ARCore 1.48.0：https://github.com/google-ar/arcore-android-sdk
  和 Brush Android 适配；已有 APK 中的 assets/third-party/ 许可证按原样保留。
- ZXing 3.5.3：Apache-2.0，https://github.com/zxing/zxing/tree/zxing-3.5.3，许可证在 tools/zxing/LICENSE 和 APK 内。
- Python 依赖：NumPy、SciPy、Pillow、qrcode 8.2；qrcode 与 colorama 的本地副本和许可位于 tools/server-python，其他许可证随其安装包提供。

此文件记录技术来源，不替代各组件及依赖自身的许可条款。
''', encoding='utf-8')
    release = {
        'created_at': datetime.now().astimezone().isoformat(),
        'mode': 'Windows runtime deployment with prebuilt Android APK; not a full source build kit',
        'apk': 'app/家居重建-v0.14.0.apk', 'version_name': '0.14.0', 'version_code': 23,
        'package': 'com.furniture.chairdemo', 'apk_sha256': EXPECTED_APK,
        'native_sha256': json.loads((ROOT/'mobile-reconstruction/verified-backend.json').read_text())['native_sha256'],
        'verification_scope': 'v0.14.0: 7 server protocol groups, 19 emulator layout/QR checks, 15 real HTTP fixture checks, 20 deletion checks passed. Existing PLY transport fixture, no new training. Camera QR and physical AR acceptance pending; see verification.json.',
        'excluded': ['server-data', 'captures', 'training runs', 'signing keys', 'virtualenv', 'Android/Rust build toolchain'],
    }
    (target/'release.json').write_text(json.dumps(release, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    files = [{'path': p.relative_to(target).as_posix(), 'bytes': p.stat().st_size, 'sha256': digest(p)}
             for p in sorted(target.rglob('*')) if p.is_file()]
    (target/'package-manifest.json').write_text(json.dumps({'files': files}, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    archive = target.with_suffix('.zip')
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for path in sorted(target.rglob('*')):
            if path.is_file():
                out.write(path, target.name+'/'+path.relative_to(target).as_posix())
    result = {'folder': str(target), 'zip': str(archive), 'bytes': archive.stat().st_size,
              'sha256': digest(archive), 'file_count': len(files)+1}
    (parent/'latest-package.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
