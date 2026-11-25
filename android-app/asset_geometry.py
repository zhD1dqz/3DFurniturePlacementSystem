"""Visible-alpha calibration for a Gaussian asset, not physical ground truth."""
import json
from pathlib import Path
import numpy as np
from scipy import ndimage
from PIL import Image


def covariance(rows):
    c=np.zeros((len(rows),3,3),dtype=float)
    c[:,range(3),range(3)]=rows[:,3:6]
    c[:,0,1]=c[:,1,0]=rows[:,6]
    c[:,0,2]=c[:,2,0]=rows[:,7]
    c[:,1,2]=c[:,2,1]=rows[:,8]
    return c


def alpha_projection(points,covs,opacity,axes,resolution=640):
    xy=points[:,axes];lo=np.percentile(xy,.1,axis=0)-.12;hi=np.percentile(xy,99.9,axis=0)+.12
    step=max(hi-lo)/(resolution-1);hi=lo+step*(resolution-1)
    log_trans=np.zeros((resolution,resolution),dtype=float)
    for centre,cov,alpha in zip(xy,covs[:,axes][:,:,axes],opacity):
        if alpha<.05:continue
        c=cov/(step*step)+np.eye(2)*.3
        if np.linalg.det(c)<=0:continue
        radius=3*np.sqrt(np.diag(c));p=(centre-lo)/step
        lower=np.maximum(0,np.floor(p-radius).astype(int));upper=np.minimum(resolution-1,np.ceil(p+radius).astype(int))
        if np.any(upper<lower):continue
        yy,xx=np.mgrid[lower[1]:upper[1]+1,lower[0]:upper[0]+1]
        dx=xx-p[0];dy=yy-p[1];inv=np.linalg.inv(c)
        a=np.minimum(.99,alpha*np.exp(-.5*(inv[0,0]*dx*dx+2*inv[0,1]*dx*dy+inv[1,1]*dy*dy)))
        a[a<1/255]=0
        log_trans[lower[1]:upper[1]+1,lower[0]:upper[0]+1]+=np.log1p(-a)
    return -np.expm1(log_trans),lo,step


def component_bounds(alpha,lo,step,threshold):
    labels,count=ndimage.label(ndimage.binary_closing(alpha>threshold,iterations=1))
    if not count:raise ValueError('No connected visible surface')
    sizes=np.bincount(labels.ravel());sizes[0]=0;mask=labels==sizes.argmax()
    y,x=np.where(mask)
    return np.array([lo+[x.min()*step,y.min()*step],lo+[x.max()*step,y.max()*step]]),mask


def calibrate(rows,raw_inverse,folder):
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    rows=rows.copy();points=rows[:,:3].astype(float);covs=covariance(rows)
    # Keep the original front/side assignment, refine horizontal heading only.
    reliable=rows[:,12]>.35
    best=None
    for angle in np.arange(-20,20.01,.25):
        t=np.radians(angle);r=np.array([[np.cos(t),0,-np.sin(t)],[0,1,0],[np.sin(t),0,np.cos(t)]])
        p=points[reliable]@r.T;span=np.percentile(p[:,[0,2]],99,axis=0)-np.percentile(p[:,[0,2]],1,axis=0)
        score=np.prod(span)
        if best is None or score<best[0]:best=(score,angle,r)
    _,angle,r=best;points=points@r.T;covs=r[None]@covs@r.T[None]
    measurements={t:[[],[],[]] for t in [.2,.35,.5]}
    for name,axes in [('front',[0,1]),('side',[2,1]),('top',[0,2])]:
        alpha,lo,step=alpha_projection(points,covs,rows[:,12],axes)
        Image.fromarray(np.uint8(np.clip(alpha,0,1)[::-1]*255)).save(folder/(name+'-alpha.png'))
        for threshold,axis_bounds in measurements.items():
            bounds,mask=component_bounds(alpha,lo,step,threshold)
            for col,axis in enumerate(axes):axis_bounds[axis].append(bounds[:,col])
            if threshold==.35:Image.fromarray(np.uint8(mask[::-1])*255).save(folder/(name+'-body.png'))
    ranges={str(k):np.stack([np.median(v,axis=0) for v in axes],axis=1) for k,axes in measurements.items()}
    # The fitted room floor is the contact reference. Do not mistake the
    # Gaussian glow below the feet for additional physical furniture height.
    for b in ranges.values():b[0,1]=0
    bounds=ranges['0.35'];span=bounds[1]-bounds[0]
    if np.any(span<.05):raise ValueError('Invalid visible body bounds')
    s=1/span[1];origin=np.array([(bounds[0,0]+bounds[1,0])/2,bounds[0,1],(bounds[0,2]+bounds[1,2])/2])
    rows[:,:3]=(points-origin)*s;covs*=s*s
    eigenvalues,eigenvectors=np.linalg.eigh(covs)
    minimum=np.maximum(1e-10,eigenvalues[:,-1]*1e-6)
    stabilized=int(np.any(eigenvalues<minimum[:,None],axis=1).sum())
    eigenvalues=np.maximum(eigenvalues,minimum[:,None])
    covs=(eigenvectors*eigenvalues[:,None,:])@np.swapaxes(eigenvectors,1,2)
    rows[:,3:6]=np.diagonal(covs,axis1=1,axis2=2);rows[:,6]=covs[:,0,1];rows[:,7]=covs[:,0,2];rows[:,8]=covs[:,1,2]
    if not np.isfinite(rows).all() or np.linalg.eigvalsh(covariance(rows)).min()<=0:raise ValueError('Invalid calibrated covariance')
    inverse=raw_inverse@r.T
    dimensions={'width_m':float(span[0]*s),'depth_m':float(span[2]*s),'height_m':1.0,
                'calibration_version':3,'measured_height_m':1.0,'source':'multi-view rendered alpha, largest connected body; approximate',
                'heading_refinement_deg':float(angle),'alpha_threshold':.35,
                'float32_covariances_stabilized':stabilized,
                'threshold_dimensions_m':{k:((v[1]-v[0])*s).tolist() for k,v in ranges.items()},
                'bounds_before_normalization':bounds.tolist(),'translation_before_normalization':origin.tolist(),'normalization_scale':float(s),
                'limitations':'Visible-alpha calibration is threshold dependent; width/depth remain estimates until physically measured. Front direction is only locally refined.'}
    (folder/'geometry-report.json').write_text(json.dumps(dimensions,indent=2)+'\n')
    assert np.allclose(inverse@inverse.T,np.eye(3),atol=1e-5)
    assert np.linalg.det(inverse)>0
    return rows,inverse,dimensions
