package com.furniture.chairdemo;

final class DimensionFit {
    static float scale(float mw,float md,float mh,float w,float d,float h){return (mw*w+md*d+4*mh*h)/(mw*mw+md*md+4*mh*mh);}
    static float residual(float mw,float md,float mh,float w,float d,float h,float s){return Math.max(Math.abs(s*mw-w)/w,Math.max(Math.abs(s*md-d)/d,Math.abs(s*mh-h)/h));}
}
