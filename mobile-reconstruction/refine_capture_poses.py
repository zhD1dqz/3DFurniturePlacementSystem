"""Development-only known-pose triangulation and BA; never edits original capture data.

Both phone arms retain exactly the original images, intrinsics and Gaussian seeds.
Only extrinsics change. BA uses image correspondences from all localization views;
evaluation views are held out of Gaussian training, not out of localization.
"""
import argparse, hashlib, json, os, shutil, sqlite3, subprocess, time
from pathlib import Path
import numpy as np
from scipy.spatial.transform import Rotation

ROOT=Path(__file__).resolve().parents[1]

def load_images(path):
    lines=path.read_text(encoding='utf-8').splitlines();out={};i=0
    while i<len(lines):
        line=lines[i].strip();i+=1
        if not line or line.startswith('#'):continue
        v=line.split();out[v[9]]=(int(v[0]),np.array(v[1:5],float),np.array(v[5:8],float),int(v[8]));i+=1
    return out

def rot(q):return Rotation.from_quat(q[[1,2,3,0]]).as_matrix()
def centre(v):return -rot(v[1]).T@v[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=ROOT/'dist/mobile-quality/desktop-reference/dataset');p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    colmap=ROOT/'tools/colmap/bin/colmap.exe';db=a.output/'features.db';env=os.environ.copy();env['QT_QPA_PLATFORM']='offscreen'
    def run(stage,*args):
        print(stage,flush=True);start=time.monotonic()
        with (a.output/(stage+'.log')).open('w',encoding='utf-8') as log:subprocess.run([str(colmap),*map(str,args)],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
        print(stage+' completed in %.1fs'%(time.monotonic()-start),flush=True)
    if not db.exists():
        run('features','feature_extractor','--database_path',db,'--image_path',a.source/'images','--ImageReader.single_camera','1','--ImageReader.camera_model','PINHOLE','--ImageReader.camera_params','438.044525146,437.486297607,320.477020264,239.160614014','--FeatureExtraction.use_gpu','0','--FeatureExtraction.num_threads','8','--SiftExtraction.peak_threshold','0.002','--SiftExtraction.estimate_affine_shape','1','--SiftExtraction.domain_size_pooling','1')
    if not (a.output/'matching.done').exists():
        run('matching','exhaustive_matcher','--database_path',db,'--FeatureMatching.use_gpu','0','--FeatureMatching.num_threads','8','--FeatureMatching.guided_matching','1');(a.output/'matching.done').touch()
    poses=load_images(a.source/'sparse/0/images.txt');known=a.output/'known';known.mkdir(exist_ok=True)
    cameras=[s.split() for s in (a.source/'sparse/0/cameras.txt').read_text(encoding='utf-8').splitlines() if s.strip() and not s.startswith('#')]
    assert len({tuple(c[1:]) for c in cameras})==1,'Diagnostic shared-camera extraction requires identical intrinsics'
    (known/'cameras.txt').write_text('1 '+' '.join(cameras[0][1:])+'\n',encoding='utf-8');(known/'points3D.txt').write_text('',encoding='utf-8')
    with sqlite3.connect(db) as conn:ids={name:i for i,name in conn.execute('SELECT image_id,name FROM images')}
    def line(v,name):return ' '.join(map(str,[v[0],*v[1],*v[2],v[3],name]))+'\n\n'
    (known/'images.txt').write_text(''.join(line((ids[name],q,t,1),name) for name,(_,q,t,c) in poses.items()),encoding='utf-8')
    tri=a.output/'triangulated';tri.mkdir(exist_ok=True)
    run('triangulate','point_triangulator','--database_path',db,'--image_path',a.source/'images','--input_path',known,'--output_path',tri,'--refine_intrinsics','0','--Mapper.ba_refine_focal_length','0','--Mapper.ba_refine_extra_params','0')
    ba=a.output/'bundle-adjusted';ba.mkdir(exist_ok=True)
    run('bundle-adjust','bundle_adjuster','--input_path',tri,'--output_path',ba,'--BundleAdjustment.refine_focal_length','0','--BundleAdjustment.refine_principal_point','0','--BundleAdjustment.refine_extra_params','0','--BundleAdjustmentCeres.max_num_iterations','100')
    txt=a.output/'refined-text';txt.mkdir(exist_ok=True);run('export','model_converter','--input_path',ba,'--output_path',txt,'--output_type','TXT');new=load_images(txt/'images.txt');assert set(new)==set(poses)
    # Align the refined camera-centre gauge to the metric ARCore world for existing crops.
    names=sorted(poses);x=np.array([centre(new[n]) for n in names]);y=np.array([centre(poses[n]) for n in names]);mx,my=x.mean(0),y.mean(0);xc,yc=x-mx,y-my;u,d,vt=np.linalg.svd(yc.T@xc/len(x));sign=np.ones(3);sign[-1]=np.linalg.det(u@vt);r=u@np.diag(sign)@vt;s=(d*sign).sum()/np.mean(np.sum(xc*xc,axis=1));t=my-s*r@mx
    changes=[];angles=[];aligned={}
    for name in names:
        v=new[name];c=s*r@centre(v)+t;rr=rot(v[1])@r.T;qq=Rotation.from_matrix(rr).as_quat()[[3,0,1,2]];aligned[name]=(poses[name][0],qq,-rr@c,poses[name][3]);changes.append(float(np.linalg.norm(c-centre(poses[name]))));angles.append(float(Rotation.from_matrix(rr@rot(poses[name][1]).T).magnitude()*180/np.pi))
    for arm,ps in [('control',poses),('refined',aligned)]:
        target=a.output/('phone-'+arm);shutil.copytree(a.source,target,dirs_exist_ok=True);(target/'sparse/0/images.txt').write_text(''.join(line(ps[n],n) for n in poses),encoding='utf-8');(target/'READY').touch()
    # Assert unchanged camera models, photo bytes and seed points between arms.
    left=a.output/'phone-control';right=a.output/'phone-refined'
    for f in [*sorted((left/'images').glob('*.jpg')),left/'sparse/0/cameras.txt',left/'sparse/0/points3D.txt']:
        assert hashlib.sha256(f.read_bytes()).digest()==hashlib.sha256((right/f.relative_to(left)).read_bytes()).digest()
    report={'images':len(poses),'same_images_intrinsics_and_seed_points':True,'similarity_alignment_scale':float(s),'similarity_alignment_rotation_rows':r.tolist(),'similarity_alignment_translation':t.tolist(),'camera_centre_correction_median_m':float(np.median(changes)),'camera_centre_correction_max_m':max(changes),'rotation_correction_median_deg':float(np.median(angles)),'rotation_correction_max_deg':max(angles),'scope':'Desktop diagnostic BA; phone optimizes Gaussians. All views used for localization; Gaussian eval split stays unchanged. No production capture or model modified.'}
    (a.output/'pose-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2),flush=True)

if __name__=='__main__':main()
