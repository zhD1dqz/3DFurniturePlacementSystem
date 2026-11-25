"""Isolated transport fixture for emulator tests. No training or production data."""
from pathlib import Path
import argparse
import shutil
from server import Engine, Server
from pipeline import ROOT, sha

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--port', type=int, default=18268)
    args = parser.parse_args()
    service = Server(('127.0.0.1', args.port), None)
    engine = Engine(args.data)
    service.engine = engine
    engine.connection['pair_code'] = '12345678'  # This fixture never binds to LAN.
    model = ROOT/'mobile-assets/chair-1m/chair.ply'
    job = 'd'*32
    engine.reserve(job, 'scan-1700000000000-12345678', 'a'*64)
    shutil.copyfile(model, engine.folder(job)/'model.ply')
    engine.update(job, status='completed', phase='completed', message='Transport fixture, no training',
                  model_sha256=sha(model), model_bytes=model.stat().st_size, elapsed_seconds=.1,
                  completed_steps=7000, requested_steps=7000, gaussians=9880)
    engine.active = None
    engine.reserve('e'*32, 'scan-1700000000001-12345678', 'b'*64)
    engine.fail_upload('e'*32, 'Fixture upload interrupted')
    print('Local test fixture ready', flush=True)
    service.serve_forever()
