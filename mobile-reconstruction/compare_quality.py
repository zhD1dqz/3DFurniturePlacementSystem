"""Compare actual phone evaluation renders with the same original capture views."""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np


def measure(capture, result):
    rows = []
    for path in sorted(Path(result).glob('frame-*.png')):
        rendered = cv2.imread(str(path)).astype(np.float64)
        source = cv2.imread(str(Path(capture)/(path.stem+'.jpg')))
        source = cv2.resize(source, (rendered.shape[1], rendered.shape[0]), interpolation=cv2.INTER_AREA).astype(np.float64)
        mse = np.mean((rendered-source)**2)
        # Standard luminance SSIM; crop window borders to avoid padding bias.
        a = cv2.cvtColor(source.astype('uint8'), cv2.COLOR_BGR2GRAY).astype(float)
        b = cv2.cvtColor(rendered.astype('uint8'), cv2.COLOR_BGR2GRAY).astype(float)
        blur = lambda image: cv2.GaussianBlur(image, (11,11), 1.5)
        ma, mb = blur(a), blur(b)
        va, vb, cov = blur(a*a)-ma*ma, blur(b*b)-mb*mb, blur(a*b)-ma*mb
        ssim = ((2*ma*mb+6.5025)*(2*cov+58.5225))/((ma*ma+mb*mb+6.5025)*(va+vb+58.5225))
        rows.append({'view':path.name,'psnr_db':float(10*np.log10(255**2/max(mse,1e-10))), 'luminance_ssim':float(ssim[5:-5,5:-5].mean())})
    return {'views':rows,'mean_psnr_db':float(np.mean([r['psnr_db'] for r in rows])), 'mean_luminance_ssim':float(np.mean([r['luminance_ssim'] for r in rows])), 'scope':'Same-capture evaluation views; whole-scene image metrics do not isolate chair detail or prove unseen-view quality.'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('capture');p.add_argument('result');p.add_argument('--report',type=Path);args=p.parse_args();r=measure(args.capture,args.result);text=json.dumps(r,indent=2)
    if args.report:args.report.write_text(text,encoding='utf-8')
    print(text)
