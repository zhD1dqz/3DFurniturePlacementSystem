"""Authenticated local-network furniture reconstruction service; no paid API."""
import argparse
import collections
import hashlib
import hmac
import http.client
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import re
import secrets
import socket
import subprocess
import threading
import time
import webbrowser
import zipfile
import sys
import io
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'tools/server-python'))

from pipeline import BudgetReached, Cancelled, ROOT, process_capture, sha

MAX_UPLOAD = 512*1024*1024


def write_json(path, data):
    temp = path.with_suffix('.pending')
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    temp.replace(path)


def unpack_capture(archive, target, capture_id):
    target.mkdir()
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if not 41 <= len(entries) <= 601:
            raise ValueError('扫描文件数量异常')
        seen, total = set(), 0
        for entry in entries:
            name = entry.filename
            if name in seen or not re.fullmatch(r'capture\.json|frame-\d{4}\.(jpg|json)', name):
                raise ValueError('扫描包包含无效或重复文件名')
            seen.add(name)
            total += entry.file_size
            if entry.file_size > 20*1024*1024 or total > 800*1024*1024:
                raise ValueError('扫描文件过大')
            with source.open(entry) as incoming, (target/name).open('wb') as output:
                size = 0
                while block := incoming.read(1024*1024):
                    size += len(block)
                    if size > entry.file_size:
                        raise ValueError('扫描包大小异常')
                    output.write(block)
        manifest = json.loads((target/'capture.json').read_text(encoding='utf-8'))
        if manifest.get('id') != capture_id or manifest.get('state') != 'completed':
            raise ValueError('扫描编号或保存状态不一致')
        images = {n[:-4] for n in seen if n.endswith('.jpg')}
        metadata = {n[:-5] for n in seen if n.startswith('frame-') and n.endswith('.json')}
        if not 20 <= len(images) <= 300 or images != metadata:
            raise ValueError('照片与相机记录数量不一致')


class Engine:
    def __init__(self, root, processor=process_capture):
        self.root, self.processor = root, processor
        root.mkdir(parents=True, exist_ok=True)
        self.jobs = root/'jobs'
        self.jobs.mkdir(exist_ok=True)
        self.lock = threading.RLock()
        self.active = None
        self.cancel = {}
        settings = root/'connection.json'
        if settings.exists():
            self.connection = json.loads(settings.read_text(encoding='utf-8'))
        else:
            self.connection = {'server_id': secrets.token_hex(12), 'pair_code': f'{secrets.randbelow(100000000):08}',
                               'token': secrets.token_urlsafe(32)}
            write_json(settings, self.connection)
        for folder in self.jobs.iterdir():
            path = folder/'status.json'
            if path.exists():
                data = json.loads(path.read_text(encoding='utf-8'))
                if data['status'] not in ('completed', 'failed', 'cancelled'):
                    data.update(status='failed', phase='failed', message='电脑服务曾中断，请重新提交')
                    write_json(path, data)

    def folder(self, job):
        if not re.fullmatch(r'[a-f0-9]{32}', job):
            raise ValueError('任务编号无效')
        return self.jobs/job

    def status(self, job):
        path = self.folder(job)/'status.json'
        if not path.is_file():
            raise FileNotFoundError('未找到任务')
        return json.loads(path.read_text(encoding='utf-8'))

    def update(self, job, **changes):
        with self.lock:
            value = self.status(job)
            value.update(changes, updated_at_ms=int(time.time()*1000))
            write_json(self.folder(job)/'status.json', value)
            return value

    def reserve(self, job, capture_id, upload_sha):
        with self.lock:
            path = self.folder(job)
            if path.exists():
                old = self.status(job)
                if old['capture_id'] != capture_id or old['upload_sha256'] != upload_sha:
                    raise ValueError('任务编号已被其他扫描使用')
                return False
            if self.active is not None:
                raise BlockingIOError('电脑正在处理另一项任务，请稍后重试')
            path.mkdir()
            self.active = job
            self.cancel[job] = threading.Event()
            write_json(path/'status.json', {'id': job, 'capture_id': capture_id, 'upload_sha256': upload_sha,
                'status': 'uploading', 'phase': 'uploading', 'message': '手机正在上传扫描照片',
                'created_at_ms': int(time.time()*1000), 'updated_at_ms': int(time.time()*1000)})
            return True

    def fail_upload(self, job, message):
        with self.lock:
            self.update(job, status='failed', phase='failed', message=message)
            if self.active == job:
                self.active = None

    def submit(self, job):
        self.update(job, status='running', phase='validating', message='电脑已收到扫描，准备处理', started_at_ms=int(time.time()*1000))
        threading.Thread(target=self.work, args=(job,), daemon=True).start()

    def work(self, job):
        folder = self.folder(job)
        try:
            status = self.status(job)
            unpack_capture(folder/'upload.zip', folder/'capture', status['capture_id'])
            result = self.processor(folder, lambda **v: self.update(job, **v), self.cancel[job])
            if self.cancel[job].is_set():
                raise Cancelled()
            self.update(job, **result, status='completed', phase='completed', message='电脑重建完成，可以下载模型',
                        model_url=f'/api/jobs/{job}/model')
        except Cancelled:
            self.update(job, status='cancelled', phase='cancelled', message='已取消电脑处理，原扫描照片已保留')
        except Exception as error:
            (folder/'error.txt').write_text(repr(error), encoding='utf-8')
            message = str(error) if isinstance(error, ValueError) else ('已到十分钟处理上限，请重试或减少重复照片' if isinstance(error, BudgetReached) else '本次电脑处理未完成，请在电脑查看错误记录')
            self.update(job, status='failed', phase='failed', message=message)
        finally:
            with self.lock:
                if self.active == job:
                    self.active = None

    def cancel_job(self, job):
        with self.lock:
            value = self.status(job)
            if value['status'] not in ('completed', 'failed', 'cancelled') and job in self.cancel:
                self.cancel[job].set()
            return value


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False
    def server_bind(self):
        # Windows SO_REUSEADDR permits two listeners on one port. Exclusive bind
        # makes a second launcher reuse the running service rather than recover
        # or overwrite its jobs.
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()
    def __init__(self, address, engine):
        super().__init__(address, Handler)
        self.engine = engine
        self.pair_attempts = collections.defaultdict(collections.deque)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        # Never log authorization headers or pairing credentials.
        print(time.strftime('%H:%M:%S'), self.client_address[0], fmt % args, flush=True)

    def send_json(self, status, value):
        self.send_bytes(status, json.dumps(value, ensure_ascii=False).encode('utf-8'), 'application/json; charset=utf-8')

    def send_bytes(self, status, data, mime):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        self.wfile.write(data)

    def authenticated(self):
        return hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer '+self.server.engine.connection['token'])

    def do_GET(self):
        try:
            if self.path == '/pairing.png':
                if self.client_address[0] not in ('127.0.0.1', '::1', self.server.server_address[0]):
                    return self.send_json(403, {'error': '请在电脑本机查看连接二维码'})
                try:
                    import qrcode
                    url = f'http://{self.server.server_address[0]}:{self.server.server_address[1]}'
                    payload = json.dumps({'type': 'furniture-pair', 'version': 1, 'url': url,
                                          'code': self.server.engine.connection['pair_code']}, separators=(',', ':'))
                    image = qrcode.make(payload)
                    out = io.BytesIO()
                    image.save(out, format='PNG')
                    return self.send_bytes(200, out.getvalue(), 'image/png')
                except ImportError:
                    return self.send_json(503, {'error': '请安装 requirements-server.txt 中的二维码依赖，或手动输入连接码'})
            if self.path == '/':
                if self.client_address[0] not in ('127.0.0.1', '::1', self.server.server_address[0]):
                    return self.send_json(403, {'error': '请在这台电脑查看连接信息'})
                e = self.server.engine
                rows = []
                for path in sorted(e.jobs.glob('*/status.json'), key=lambda p: p.stat().st_mtime, reverse=True)[:10]:
                    j = json.loads(path.read_text(encoding='utf-8'))
                    rows.append('<p>'+html.escape(j['capture_id'])+' · '+html.escape(j['message'])+'</p>')
                url = f'http://{self.server.server_address[0]}:{self.server.server_address[1]}'
                page = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta http-equiv="refresh" content="8"><meta name="viewport" content="width=device-width"><title>家居重建 · 电脑处理</title><body style="background:#101a20;color:#e5f5f3;font:18px system-ui;max-width:800px;margin:60px auto;padding:24px"><h1>电脑处理已就绪</h1><p>手机与电脑连接同一个 Wi-Fi，电脑保持开机。</p><p>手机连接地址：<b>'+url+'</b></p><p>连接码：<b style="font-size:32px;color:#85dfc8">'+e.connection['pair_code']+'</b></p><p>在手机选择“电脑生成”，连接一次后即可上传、查看进度和下载模型。</p><hr><h2>最近任务</h2>'+(''.join(rows) or '<p>等待手机上传扫描</p>')+'</body></html>'
                page = page.replace('<hr>', '<p>手机点“扫码连接电脑”，扫描下方二维码：</p><img src="/pairing.png" width="300" height="300" alt="二维码暂不可用时请手动输入地址与连接码"><hr>', 1)
                return self.send_bytes(200, page.encode('utf-8'), 'text/html; charset=utf-8')
            if self.path == '/api/health':
                return self.send_json(200, {'name': '家居重建电脑处理', 'protocol': 1, 'version': '0.14.0', 'capabilities': ['pairing-qr', 'job-history'], 'server_id': self.server.engine.connection['server_id'], 'busy': self.server.engine.active is not None})
            if not self.authenticated():
                return self.send_json(401, {'error': '请先连接电脑'})
            if self.path == '/api/jobs':
                jobs = []
                for path in self.server.engine.jobs.glob('*/status.json'):
                    try:
                        value = json.loads(path.read_text(encoding='utf-8'))
                        jobs.append(value)
                    except (OSError, ValueError):
                        continue
                jobs.sort(key=lambda j: j.get('updated_at_ms', j.get('created_at_ms', 0)), reverse=True)
                return self.send_json(200, {'jobs': jobs[:100]})
            match = re.fullmatch(r'/api/jobs/([a-f0-9]{32})(/model)?', self.path)
            if not match:
                return self.send_json(404, {'error': '未找到接口'})
            job = self.server.engine.status(match[1])
            if not match[2]:
                return self.send_json(200, job)
            if job['status'] != 'completed':
                return self.send_json(409, {'error': '模型尚未完成'})
            file = self.server.engine.folder(match[1])/'model.ply'
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(file.stat().st_size))
            self.send_header('Connection', 'close')
            self.send_header('X-Model-Sha256', job['model_sha256'])
            self.end_headers()
            self.close_connection = True
            with file.open('rb') as stream:
                while block := stream.read(1024*1024):
                    self.wfile.write(block)
        except FileNotFoundError:
            self.send_json(404, {'error': '未找到任务，请重新提交'})
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        try:
            if self.path == '/api/pair':
                if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                    return self.send_json(415, {'error': '请求格式无效'})
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 1024:
                    return self.send_json(400, {'error': '连接信息无效'})
                attempts = self.server.pair_attempts[self.client_address[0]]
                now = time.monotonic()
                while attempts and attempts[0] < now-60:
                    attempts.popleft()
                if len(attempts) >= 10:
                    return self.send_json(429, {'error': '连接尝试过多，请一分钟后重试'})
                attempts.append(now)
                self.connection.settimeout(15)
                code = json.loads(self.rfile.read(size)).get('code', '')
                if not isinstance(code, str) or not hmac.compare_digest(code, self.server.engine.connection['pair_code']):
                    return self.send_json(401, {'error': '连接码不正确，请查看电脑页面'})
                return self.send_json(200, {'token': self.server.engine.connection['token'], 'server_id': self.server.engine.connection['server_id']})
            match = re.fullmatch(r'/api/jobs/([a-f0-9]{32})/cancel', self.path)
            if not self.authenticated():
                return self.send_json(401, {'error': '请先连接电脑'})
            if match:
                return self.send_json(200, self.server.engine.cancel_job(match[1]))
            self.send_json(404, {'error': '未找到接口'})
        except FileNotFoundError:
            self.send_json(404, {'error': '未找到任务'})
        except (ValueError, json.JSONDecodeError):
            self.send_json(400, {'error': '连接请求无效'})

    def do_PUT(self):
        job, reserved = None, False
        try:
            if not self.authenticated():
                return self.send_json(401, {'error': '请先连接电脑'})
            match = re.fullmatch(r'/api/jobs/([a-f0-9]{32})', self.path)
            capture_id, digest = self.headers.get('X-Capture-Id', ''), self.headers.get('X-Upload-Sha256', '')
            if not match or not re.fullmatch(r'scan-\d+-[a-f0-9]{8}', capture_id) or not re.fullmatch(r'[a-f0-9]{64}', digest):
                return self.send_json(400, {'error': '任务信息无效'})
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= MAX_UPLOAD:
                return self.send_json(413, {'error': '扫描包过大，请减少重复照片'})
            job = match[1]
            reserved = self.server.engine.reserve(job, capture_id, digest)
            self.connection.settimeout(60)
            output = self.server.engine.folder(job)/('upload.zip.part' if reserved else 'retry-upload.part')
            value, remaining = hashlib.sha256(), size
            with output.open('wb') as stream:
                while remaining:
                    block = self.rfile.read(min(1024*1024, remaining))
                    if not block:
                        raise ValueError('上传中断，请重新上传')
                    stream.write(block)
                    value.update(block)
                    remaining -= len(block)
            if not hmac.compare_digest(value.hexdigest(), digest):
                raise ValueError('上传文件校验失败，请重新上传')
            if reserved:
                output.replace(output.with_name('upload.zip'))
                self.server.engine.submit(job)
            else:
                output.unlink()
            self.send_json(202, self.server.engine.status(job))
        except BlockingIOError as error:
            self.send_json(409, {'error': str(error)})
        except (ValueError, OSError) as error:
            if reserved:
                self.server.engine.fail_upload(job, '上传未完成，请重试')
            self.send_json(400, {'error': str(error)})


def lan_address():
    try:
        result = subprocess.run(['powershell.exe', '-NoProfile', '-Command',
            "Get-NetIPConfiguration | Where-Object {$_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up'} | ForEach-Object {$_.IPv4Address.IPAddress}"],
            capture_output=True, text=True, timeout=15)
        for line in result.stdout.splitlines():
            if ipaddress.ip_address(line.strip()).is_private:
                return line.strip()
    except Exception:
        pass
    for value in socket.gethostbyname_ex(socket.gethostname())[2]:
        if value.startswith('192.168.'):
            return value
    raise ValueError('没有找到局域网地址，请先让电脑连接Wi-Fi或网线')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='auto')
    parser.add_argument('--port', type=int, default=8268)
    parser.add_argument('--data', type=Path, default=ROOT/'server-data')
    parser.add_argument('--open', action='store_true')
    args = parser.parse_args()
    host = lan_address() if args.host == 'auto' else args.host
    if not (ipaddress.ip_address(host).is_private or ipaddress.ip_address(host).is_loopback):
        raise ValueError('第一版只允许局域网地址')
    url = f'http://{host}:{args.port}'
    # Acquire the listening port before recovering persisted jobs. A second
    # launcher must never mark another running service's live jobs as interrupted.
    try:
        server = Server((host, args.port), None)
    except OSError:
        connection = http.client.HTTPConnection(host, args.port, timeout=3)
        try:
            connection.request('GET', '/api/health')
            response = connection.getresponse()
            existing = json.loads(response.read())
            if response.status != 200 or existing.get('protocol') != 1:
                raise ValueError('端口已被其他程序使用')
            print('电脑处理服务已经运行：'+url, flush=True)
            if args.open:
                webbrowser.open(url)
            return
        finally:
            connection.close()
    try:
        engine = Engine(args.data)
        server.engine = engine
    except Exception:
        server.server_close()
        raise
    print('电脑处理已启动。请在这台电脑打开 '+url+' 查看手机连接码。', flush=True)
    if args.open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for flag in engine.cancel.values():
            flag.set()
        deadline = time.monotonic()+10
        while engine.active is not None and time.monotonic() < deadline:
            time.sleep(.1)
        server.server_close()


if __name__ == '__main__':
    main()
