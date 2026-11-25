"""Validate completed phone artifacts and make an equal-resolution diagnostic sheet."""
import argparse,hashlib,json,sys
from pathlib import Path
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from compare_region_quality import compare
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from inspect_result import read_gaussians

def main():
    p=argparse.ArgumentParser();p.add_argument('folder',type=Path);a=p.parse_args();capture=ROOT/'mobile-reconstruction/captures/scan-1793555356006-bec01522'
    result=compare(capture,a.folder/'control',a.folder/'refined',ROOT/'dist/mobile-crop-v09/model.json');(a.folder/'image-comparison.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    info={}
    for arm in ('control','refined'):
        r=json.loads((a.folder/arm/'phone-report.json').read_text(encoding='utf-8'));native=r['native_result'];assert native['phase']=='completed' and native['completed_steps']==1200 and native['wall_seconds']<180
        path=a.folder/arm/native['model'];records=read_gaussians(path);assert len(records)==native['gaussians'];assert all(np.isfinite(records[k]).all() for k in records.dtype.names);assert np.all(np.linalg.norm(np.column_stack([records['rot_'+str(i)] for i in range(4)]),axis=1)>1e-8)
        info[arm]={'gaussians':len(records),'seconds':native['wall_seconds'],'training_views':native['training_views'],'all_parameters_finite':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    deltas={k:result['means']['trial']['crop_region'][k]-result['means']['baseline']['crop_region'][k] for k in ('psnr_db','ssim')}
    counts={k:sum(v['trial']['crop_region'][k]>v['baseline']['crop_region'][k] for v in result['views']) for k in ('psnr_db','ssim')}
    report={'artifacts':info,'evaluation_views':len(result['views']),'means':result['means'],'target_region_deltas':deltas,'views_with_target_improvement':counts,'production_app_updated':False,'scope':'Single-run same-capture diagnostic. All photos used for localization; eval photos excluded only from Gaussian training. Projected crop includes background. Not proof of phone-only pose correction or independent generalization.'}
    (a.folder/'quality-summary.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    font=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',22);small=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',18);sheet=Image.new('RGB',(384*3,512*3+70),(16,25,31));draw=ImageDraw.Draw(sheet)
    for i,label in enumerate(['Original capture','Phone: original poses','Phone: refined poses']):draw.text((i*384+12,12),label,font=font,fill='white')
    for y,index in enumerate([0,48,96]):
        for x,path in enumerate([capture/('frame-%04d.jpg'%index),a.folder/'control'/('frame-%04d.png'%index),a.folder/'refined'/('frame-%04d.png'%index)]):
            image=cv2.imread(str(path));image=cv2.resize(image,(512,384),interpolation=cv2.INTER_AREA);image=cv2.rotate(image,cv2.ROTATE_90_CLOCKWISE);sheet.paste(Image.fromarray(cv2.cvtColor(image,cv2.COLOR_BGR2RGB)),(x*384,52+y*512))
    sheet.save(a.folder/'pose-comparison.png');print(json.dumps(report,indent=2))

if __name__=='__main__':main()
