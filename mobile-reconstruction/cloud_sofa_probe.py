"""One explicit Tripo cloud diagnostic, with a local-only prepare mode by default.

Read TRIPO_API_KEY from this process environment. Never log/store credentials.
No paid POST retries; task polling can resume without resubmitting generation.
"""
import argparse,hashlib,json,mimetypes,os,re,time,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError

BASE='https://openapi.tripo3d.ai/v3'

def api(path,key,payload=None,content_type='application/json'):
    headers={'Authorization':'Bearer '+key,'Content-Type':content_type}
    body=payload if isinstance(payload,bytes) else None if payload is None else json.dumps(payload).encode()
    try:
        with urlopen(Request(BASE+path,data=body,headers=headers),timeout=60) as r:value=json.load(r)
    except HTTPError as e:raise RuntimeError('Service returned HTTP %d; no automatic paid retry.'%e.code) from None
    except URLError:raise RuntimeError('Network request failed; inspect saved task state before retrying submission.') from None
    if value.get('code')!=0:raise RuntimeError('Service returned error code %s.'%value.get('code'))
    return value['data']

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','submit','poll']);p.add_argument('--image',type=Path,default=Path('dist/buyer-sofa-study/cloud-inputs/front-cutout.png'));p.add_argument('--output',type=Path,default=Path('dist/buyer-sofa-study/cloud-result'));p.add_argument('--confirm-one-paid-task',action='store_true');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);statefile=a.output/'task.json'
    if a.action=='prepare':
        b=a.image.read_bytes();assert b.startswith(b'\x89PNG') and len(b)<=20*1024*1024
        plan={'endpoint':BASE+'/generation/image-to-splat','image':str(a.image.resolve()),'image_sha256':hashlib.sha256(b).hexdigest(),'request_after_upload':{'input':'<returned_file_token>','model_seed':42},'documented_credits':30,'typical_minutes':4,'accepted_wait_minutes':10,'network_requests_made':0,'state':'prepared_not_submitted'}
        (a.output/'request-plan.json').write_text(json.dumps(plan,indent=2),encoding='utf-8');print(json.dumps(plan,indent=2));return
    key=os.environ.get('TRIPO_API_KEY');
    if not key:raise RuntimeError('TRIPO_API_KEY is not configured locally. Do not send it in chat.')
    if a.action=='submit':
        if not a.confirm_one_paid_task:raise RuntimeError('Submitting consumes service credits; explicit one-task confirmation is required.')
        if statefile.exists():raise RuntimeError('A task already exists; use poll rather than creating a duplicate paid task.')
        b=a.image.read_bytes();assert len(b)<=20*1024*1024;boundary='----Furniture'+uuid.uuid4().hex
        # Only the selected image goes to the service; original screenshots stay local.
        body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="furniture.png"\r\nContent-Type: image/png\r\n\r\n').encode()+b+('\r\n--'+boundary+'--\r\n').encode()
        token=api('/files',key,body,'multipart/form-data; boundary='+boundary)['file_token']
        (a.output/'upload.json').write_text(json.dumps({'file_token':token,'image_sha256':hashlib.sha256(b).hexdigest()},indent=2),encoding='utf-8')
        task=api('/generation/image-to-splat',key,{'input':token,'model_seed':42});taskid=task['task_id']
        if not re.fullmatch(r'[A-Za-z0-9_-]+',taskid):raise RuntimeError('Unexpected task identifier')
        statefile.write_text(json.dumps({'task_id':taskid,'state':'submitted','representation':'Gaussian .splat','submitted_at':time.time()},indent=2),encoding='utf-8');print('One task submitted:',taskid);return
    state=json.loads(statefile.read_text(encoding='utf-8'));taskid=state['task_id'];assert re.fullmatch(r'[A-Za-z0-9_-]+',taskid)
    value=api('/tasks/'+taskid,key);(a.output/'latest-task-result.json').write_text(json.dumps(value,indent=2),encoding='utf-8');print(json.dumps({'task_id':taskid,'status':value.get('status'),'progress':value.get('progress'),'credits_consumed':value.get('credits_consumed')},indent=2))
    # Download is explicit after review of this result; signed URLs/keys aren't printed.

if __name__=='__main__':main()
