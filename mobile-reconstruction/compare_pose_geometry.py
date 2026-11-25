"""Image-match epipolar diagnostic for original and refined poses, at several gaps.
Uses the same capture as pose fitting; not an independent validation dataset.
"""
import argparse,json
from pathlib import Path
import cv2
import numpy as np
from refine_capture_poses import load_images,rot

def main():
    p=argparse.ArgumentParser();p.add_argument('capture',type=Path);p.add_argument('experiment',type=Path);a=p.parse_args();detector=cv2.SIFT_create(nfeatures=1600);matcher=cv2.BFMatcher();cv2.setRNGSeed(42)
    models={arm:load_images(a.experiment/('phone-'+arm)/'sparse/0/images.txt') for arm in ('control','refined')};names=sorted(models['control']);cache={}
    def image(name):
        if name not in cache:
            meta=json.loads((a.capture/(Path(name).stem+'.json')).read_text(encoding='utf-8'));key,desc=detector.detectAndCompute(cv2.imread(str(a.capture/name),cv2.IMREAD_GRAYSCALE),None);fx,fy=meta['focal_length_px'];cx,cy=meta['principal_point_px'];cache[name]=(key,desc,np.array([[fx,0,cx],[0,fy,cy],[0,0,1.]]))
        return cache[name]
    rows=[]
    for gap in (1,5,10,20):
        for i in range(0,len(names)-gap,10):
            n,m=names[i],names[i+gap];ka,da,k1=image(n);kb,db,k2=image(m)
            matches=[x for x,y in matcher.knnMatch(da,db,k=2) if x.distance<.75*y.distance];
            if len(matches)<20:continue
            x=np.array([ka[z.queryIdx].pt for z in matches]);y=np.array([kb[z.trainIdx].pt for z in matches]);_,mask=cv2.findFundamentalMat(x,y,cv2.FM_RANSAC,1.5,.999)
            if mask is None or mask.sum()<20:continue
            x=np.column_stack([x,np.ones(len(x))]);y=np.column_stack([y,np.ones(len(y))]);keep=mask.ravel().astype(bool);row={'first':n,'second':m,'gap':gap,'image_inliers':int(keep.sum())}
            for arm,model in models.items():
                pa,pb=model[n],model[m];ra,rb=rot(pa[1]),rot(pb[1]);r=rb@ra.T;t=pb[2]-r@pa[2];cross=np.array([[0,-t[2],t[1]],[t[2],0,-t[0]],[-t[1],t[0],0]]);f=np.linalg.inv(k2).T@cross@r@np.linalg.inv(k1);fx=x@f.T;fty=y@f;err=np.sqrt(np.sum(y*fx,axis=1)**2/np.maximum(1e-30,np.sum(fx[:,:2]**2,axis=1)+np.sum(fty[:,:2]**2,axis=1)))[keep];row[arm]={'median_px':float(np.median(err)),'p90_px':float(np.percentile(err,90))}
            rows.append(row)
    means={arm:float(np.median([r[arm]['median_px'] for r in rows])) for arm in models}
    report={'pairs':rows,'median_of_pair_medians_px':means,'scope':'Same-capture image-RANSAC correspondences at gaps 1/5/10/20; localization fitting used these images, not independent ground truth; does not establish Gaussian image quality.'};(a.experiment/'geometry-comparison.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps({'pairs':len(rows),'median_px':means},indent=2))

if __name__=='__main__':main()
