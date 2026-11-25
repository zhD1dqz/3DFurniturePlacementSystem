"""Protocol tests use a temporary service and a fake worker, never a real training job."""
import hashlib
import http.client
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import zipfile

from server import Engine, Server, unpack_capture
from pipeline import Cancelled


def archive(unsafe=False):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as out:
        out.writestr('capture.json', json.dumps({'id': 'scan-1000-12345678', 'state': 'completed'}))
        for i in range(20):
            out.writestr(f'frame-{i:04d}.jpg', b'fake photo')
            out.writestr(f'frame-{i:04d}.json', '{}')
        if unsafe:
            out.writestr('../escape.txt', 'must not be written')
    return data.getvalue()


class ProtocolTests(unittest.TestCase):
    def test_history_is_authenticated_and_does_not_start_training(self):
        self.assertEqual(self.call('GET', '/api/jobs', auth=False)[0], 401)
        code, body = self.call('GET', '/api/jobs')
        self.assertEqual((code, json.loads(body)['jobs']), (200, []))
        job = 'c'*32
        self.engine.reserve(job, 'scan-1000-12345678', 'd'*64)
        self.engine.fail_upload(job, 'Upload interrupted')
        code, body = self.call('GET', '/api/jobs')
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body)['jobs'][0]['id'], job)
        self.assertEqual(json.loads(body)['jobs'][0]['status'], 'failed')
        self.assertEqual(self.runs, 0)

    def test_pairing_qr_is_local_only(self):
        status, body = self.call('GET', '/pairing.png', auth=False)
        self.assertEqual(status, 200)
        self.assertTrue(body.startswith(b'\x89PNG'))
        self.assertNotIn(self.engine.connection['token'].encode(), body)
        from server import Handler
        remote = Handler.__new__(Handler)
        remote.path = '/pairing.png'
        remote.client_address = ('192.168.1.99', 12345)
        remote.server = self.server
        captured = []
        remote.send_json = lambda status, value: captured.append(status)
        remote.do_GET()
        self.assertEqual(captured, [403])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.runs = 0
        def worker(folder, update, cancelled):
            self.runs += 1
            for _ in range(15):
                if cancelled.is_set():
                    raise Cancelled()
                time.sleep(.01)
            payload = b'test model transport payload'
            (folder/'model.ply').write_bytes(payload)
            return {'model_sha256': hashlib.sha256(payload).hexdigest(), 'model_bytes': len(payload),
                    'gaussians': 0, 'completed_steps': 1, 'requested_steps': 1, 'elapsed_seconds': .15}
        self.engine = Engine(Path(self.temp.name), processor=worker)
        self.server = Server(('127.0.0.1', 0), self.engine)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        for flag in self.engine.cancel.values():
            flag.set()
        self.server.shutdown()
        self.server.server_close()
        time.sleep(.2)
        self.temp.cleanup()

    def call(self, method, path, body=None, auth=True, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        h = {} if headers is None else dict(headers)
        if auth:
            h['Authorization'] = 'Bearer '+self.engine.connection['token']
        connection.request(method, path, body=body, headers=h)
        response = connection.getresponse()
        value = response.read()
        status = response.status
        connection.close()
        return status, value

    def put(self, job, data):
        return self.call('PUT', '/api/jobs/'+job, data, headers={'X-Capture-Id': 'scan-1000-12345678',
                         'X-Upload-Sha256': hashlib.sha256(data).hexdigest()})

    def done(self, job):
        deadline = time.monotonic()+5
        while time.monotonic() < deadline:
            result = json.loads(self.call('GET', '/api/jobs/'+job)[1])
            if result['status'] in ('completed', 'failed', 'cancelled'):
                return result
            time.sleep(.03)
        self.fail('worker did not finish')

    def test_pairing_and_authentication(self):
        self.assertEqual(self.call('GET', '/api/jobs/'+'a'*32, auth=False)[0], 401)
        code, _ = self.call('POST', '/api/pair', b'{"code":"incorrect"}', auth=False, headers={'Content-Type':'application/json'})
        self.assertEqual(code, 401)
        code, value = self.call('POST', '/api/pair', json.dumps({'code': self.engine.connection['pair_code']}).encode(), auth=False, headers={'Content-Type':'application/json'})
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(value)['token'], self.engine.connection['token'])

    def test_only_one_listener_can_own_the_service_port(self):
        with self.assertRaises(OSError):
            Server(self.server.server_address, None)

    def test_upload_download_and_idempotency(self):
        job, data = 'a'*32, archive()
        self.assertEqual(self.put(job, data)[0], 202)
        result = self.done(job)
        self.assertEqual(result['status'], 'completed')
        code, model = self.call('GET', '/api/jobs/'+job+'/model')
        self.assertEqual(code, 200)
        self.assertEqual(hashlib.sha256(model).hexdigest(), result['model_sha256'])
        self.assertEqual(self.put(job, data)[0], 202)
        self.assertEqual(self.runs, 1)

    def test_single_gpu_job_and_cancel(self):
        first, second = 'a'*32, 'b'*32
        self.assertEqual(self.put(first, archive())[0], 202)
        self.assertEqual(self.put(second, archive())[0], 409)
        self.assertEqual(self.call('POST', '/api/jobs/'+first+'/cancel', b'')[0], 200)
        self.assertEqual(self.done(first)['status'], 'cancelled')

    def test_archive_traversal_and_checksum_rejected(self):
        payload = archive(unsafe=True)
        self.assertEqual(self.put('a'*32, payload)[0], 202)
        self.assertEqual(self.done('a'*32)['status'], 'failed')
        self.assertFalse((Path(self.temp.name)/'jobs'/('a'*32)/'escape.txt').exists())
        data = archive()
        code, _ = self.call('PUT', '/api/jobs/'+'b'*32, data, headers={'X-Capture-Id':'scan-1000-12345678','X-Upload-Sha256':'0'*64})
        self.assertEqual(code, 400)
        self.assertEqual(self.engine.status('b'*32)['status'], 'failed')


if __name__ == '__main__':
    unittest.main()
