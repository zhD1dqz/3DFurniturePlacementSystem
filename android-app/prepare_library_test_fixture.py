"""Independent Python reference for Android's model crop/transform tests."""
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from inspect_result import read_gaussians


def prepare(destination,rules_folder):
    destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    source=ROOT/'mobile-reconstruction/results/20261101_175731_312302/generated_1200.ply'
    records=read_gaussians(source);rules=json.loads((Path(rules_folder)/'capture-rules.json').read_text())
    xyz=np.column_stack([records[k] for k in ('x','y','z')]).astype(float)
    inside=np.zeros(len(records),int);outside=inside.copy()
    for view in rules['views']:
        with gzip.open(Path(rules_folder)/view['file'],'rb') as stream:
            w,h=struct.unpack('<II',stream.read(8));mask=np.frombuffer(stream.read(),dtype='u1').reshape(h,w)
        p=xyz@np.array(view['rotation_rows']).T+view['translation'];fx,fy,cx,cy=view['intrinsics']
        with np.errstate(divide='ignore',invalid='ignore'):
            u=p[:,0]/p[:,2]*fx+cx;v=p[:,1]/p[:,2]*fy+cy
        visible=np.isfinite(u)&np.isfinite(v)&(p[:,2]>.001)&(u>=0)&(v>=0)&(u<w)&(v<h)
        ids=np.flatnonzero(visible);yes=mask[v[ids].astype(int),u[ids].astype(int)]>0
        inside[ids[yes]]+=1;outside[ids[~yes]]+=1
    keep=np.flatnonzero((inside>=3)&(outside==0))
    rotation=np.array(rules['rotation_rows']);scale=rules['uniform_scale'];translation=np.array(rules['translation_m'])
    rows=np.zeros((len(keep),37),dtype='<f4');rows[:,:3]=xyz[keep]@rotation.T*scale+translation
    for index,record in enumerate(records[keep]):
        q=np.array([record['rot_'+str(i)] for i in range(4)],dtype=float);q/=np.linalg.norm(q);w,x,y,z=q
        orient=np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])
        axes=scale*rotation@orient@np.diag(np.exp([float(record['scale_'+str(i)]) for i in range(3)]));cov=axes@axes.T
        rows[index,3:9]=cov[0,0],cov[1,1],cov[2,2],cov[0,1],cov[0,2],cov[1,2]
        a,b,c,d,e,f=rows[index,3:9].astype(float)
        if not (a>0 and b>0 and c>0 and a*b-d*d>0 and a*b*c+2*d*e*f-a*f*f-b*e*e-c*d*d>0):
            rows[index,3:6]+=max(1e-10,np.trace(cov)*1e-6)
        rows[index,9:12]=[record['f_dc_'+str(i)] for i in range(3)];rows[index,12]=1/(1+np.exp(-float(record['opacity'])))
        for j in range(8):rows[index,13+j*3:16+j*3]=[record['f_rest_'+str(c*8+j)] for c in range(3)]
    with (destination/'expected-model.bin').open('wb') as stream:
        stream.write(struct.pack('<II',0x31534746,len(rows)));stream.write(rotation.T.astype('<f4').tobytes(order='F'));stream.write(rows.tobytes())
    (destination/'generated-chair.ply').write_bytes(source.read_bytes())
    report={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'capture_id':rules['capture_id'],'retained_gaussians':len(keep),'retained_source_indices_sha256':hashlib.sha256(keep.astype('<i4').tobytes()).hexdigest()}
    (destination/'library-reference.json').write_text(json.dumps(report),encoding='utf-8')
    return report
