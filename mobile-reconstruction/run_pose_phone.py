"""Run the two immutable pose-diagnostic datasets in the isolated phone probe only."""
import argparse,base64,hashlib,io,json,re,subprocess,tarfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];ADB=r'D:\Android SDK\platform-tools\adb.exe';PKG='com.furniture.reconstructionprobe'

def main():
    p=argparse.ArgumentParser();p.add_argument('folder',type=Path);p.add_argument('--arm',choices=['control','refined'],required=True);a=p.parse_args();folder=a.folder/a.arm;folder.mkdir(exist_ok=True)
    def adb(*args,data=None,required=True,timeout=20):
        r=subprocess.run([ADB,'-s','537aab3f',*args],input=data,capture_output=True,timeout=timeout)
        if required and r.returncode:raise RuntimeError((r.stdout+r.stderr).decode('utf-8',errors='replace'))
        return r.stdout
    def read(path,required=True):
        b=adb('exec-out','run-as',PKG,'cat','files/'+path,required=False)
        if b.startswith(b'cat:'):
            if required:raise IOError(b.decode())
            return b''
        return b
    lock=adb('shell','dumpsys','window','policy').decode('utf-8',errors='replace')
    if re.search(r'\bshowing=true\b',lock):raise RuntimeError('Please unlock the phone before the diagnostic; original App and data were not changed.')
    build=json.loads((ROOT/'dist/mobile-probe/build-report.json').read_text(encoding='utf-8'));verified=json.loads((ROOT/'mobile-reconstruction/verified-backend.json').read_text(encoding='utf-8'));assert build['native_sha256']==verified['native_sha256'];apk=Path(build['apk']);assert hashlib.sha256(apk.read_bytes()).hexdigest()==build['apk_sha256']
    install=adb('install','-r',str(apk),timeout=60);assert b'Success' in install;print('Isolated probe installed; production App untouched.',flush=True)
    source=a.folder/('phone-'+a.arm);assert (source/'READY').exists();name='quality-pose-'+a.arm;buffer=io.BytesIO()
    with tarfile.open(fileobj=buffer,mode='w') as tar:
        for f in sorted(source.rglob('*')):
            if f.is_file():tar.add(f,arcname=name+'/'+f.relative_to(source).as_posix())
    adb('exec-in','run-as',PKG,'tar','-xf','-','-C','files',data=buffer.getvalue(),timeout=60)
    config={'total_steps':1200,'disable_refinement':False,'refine_every':100,'subsample_frames':1,'resolution':512};encoded=base64.urlsafe_b64encode(json.dumps(config).encode()).decode();previous=read('latest-output.txt',False).decode(errors='replace').strip()
    adb('shell','am','start','-S','-W','-n',PKG+'/.MainActivity','--ei','probe_steps','1200','--es','probe_dataset_name',name,'--es','probe_config_b64',encoded)
    start=time.monotonic();task=None;marker=None;done=None
    while time.monotonic()-start<180:
        if task is None:
            candidate=read('latest-output.txt',False).decode(errors='replace').strip()
            if candidate!=previous and re.fullmatch(r'probe-\d+-1200',candidate):task=candidate
        if task:
            data=read(task+'/progress.json',False)
            try:r=json.loads(data);(folder/'latest-progress.json').write_text(json.dumps(r,indent=2),encoding='utf-8')
            except ValueError:r={}
            mark=(r.get('phase'),r.get('iteration'))
            if marker!=mark:print(a.arm,mark, r.get('wall_seconds',''),flush=True);marker=mark
            if r.get('phase') in ('completed','failed','timed_out'):done=r;break
        time.sleep(3)
    if done is None:
        adb('shell','am','force-stop',PKG)
        (folder/('timeout-%d.json'%time.time())).write_text(json.dumps({'phase':'timed_out','task':task,'seconds':time.monotonic()-start,'counts_as_valid_comparison':False},indent=2),encoding='utf-8')
        raise TimeoutError('180 second phone diagnostic budget reached')
    (folder/'phone-progress.json').write_text(json.dumps(done,indent=2),encoding='utf-8')
    assert done.get('phase')=='completed',done
    assert done.get('completed_steps')==1200 and not done.get('stopped_for_time_budget'),done
    for f in ['result.json','probe-config.json','metrics.jsonl',done['model']]:
        assert '/' not in f;(folder/f).write_bytes(read(task+'/'+f))
    for i in range(0,113,8):
        f='frame-%04d.png'%i;b=read(task+'/eval_1200/'+f);assert b.startswith(b'\x89PNG');(folder/f).write_bytes(b)
    report={'task':task,'arm':a.arm,'dataset':name,'native_sha256':build['native_sha256'],'native_result':done,'observed_seconds':time.monotonic()-start,'scope':'Isolated phone training; desktop pose diagnostic; Gaussian evaluation views were used for localization, not Gaussian training; installed production App unchanged'}
    (folder/'phone-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2),flush=True)

if __name__=='__main__':main()
