"""Local diagnostic only: test photogrammetric registration of buyer photo groups.
No cloud uploads, inferred geometry, or promotion to the phone model library.
"""
import json,os,sqlite3,subprocess,time
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'dist/buyer-sofa-study';COLMAP=ROOT/'tools/colmap/bin/colmap.exe'

def main():
    rows=[];env=os.environ.copy();env['QT_QPA_PLATFORM']='offscreen'
    for group in ['pro_room_a','pro_room_b','mini_room_c','mini_room_d','mixed']:
        folder=OUT/group;masks=folder/'masks';masks.mkdir(exist_ok=True)
        for f in (folder/'images').glob('*.jpg'):
            im=Image.open(f);mask=Image.new('L',im.size,255);draw=ImageDraw.Draw(mask);draw.rectangle((int(im.width*.88),0,im.width,im.height),fill=0);draw.rectangle((0,im.height-75,im.width,im.height),fill=0);mask.save(masks/(f.name+'.png'))
        def run(stage,*args,allow_failure=False):
            with (folder/(stage+'.log')).open('w',encoding='utf-8') as log:r=subprocess.run([str(COLMAP),*map(str,args)],env=env,stdout=log,stderr=subprocess.STDOUT)
            if r.returncode and not allow_failure:raise RuntimeError(stage+' failed; see log')
            return r.returncode
        print(group,flush=True);db=folder/'features.db';start=time.monotonic()
        run('features','feature_extractor','--database_path',db,'--image_path',folder/'images','--ImageReader.mask_path',masks,'--ImageReader.camera_model','SIMPLE_RADIAL','--ImageReader.single_camera','0','--FeatureExtraction.use_gpu','0','--FeatureExtraction.num_threads','8','--FeatureExtraction.max_image_size','1024','--SiftExtraction.peak_threshold','.002','--SiftExtraction.estimate_affine_shape','1','--SiftExtraction.domain_size_pooling','1')
        run('matching','exhaustive_matcher','--database_path',db,'--FeatureMatching.use_gpu','0','--FeatureMatching.guided_matching','1','--FeatureMatching.num_threads','8')
        sparse=folder/'sparse';sparse.mkdir(exist_ok=True);mapping_status=run('mapping','mapper','--database_path',db,'--image_path',folder/'images','--output_path',sparse,'--Mapper.min_model_size','2',allow_failure=True)
        counts=[]
        for m in sorted(sparse.iterdir()):
            if (m/'images.bin').exists():
                import struct
                counts.append({'folder':str(m.relative_to(OUT)),'registered':struct.unpack('<Q',(m/'images.bin').read_bytes()[:8])[0]})
        with sqlite3.connect(db) as c:matches=list(c.execute('SELECT rows FROM two_view_geometries'))
        row={'group':group,'input_photos':len(list((folder/'images').glob('*.jpg'))),'models':counts,'mapping_exit_code':mapping_status,'verified_pairs':sum(n[0]>=15 for n in matches),'elapsed_seconds':time.monotonic()-start};rows.append(row);print(json.dumps(row),flush=True)
    (OUT/'registration-report.json').write_text(json.dumps({'groups':rows,'scope':'Photo screenshot crops with UI masks; local COLMAP diagnostic; registration can follow background, not proof of faithful sofa reconstruction.'},indent=2),encoding='utf-8')

if __name__=='__main__':main()
