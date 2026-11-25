package com.furniture.chairdemo;

import android.opengl.Matrix;
import java.nio.*;
import java.util.Arrays;

/** Metric depth filtering. Quality here is an internal weight, NOT a Full-Depth confidence map. */
final class DepthStabilizer {
    int width,height,validCount;float[] previous,quality,age;
    float[] previousView,previousProjection,previousUv;
    long previousTime;
    ByteBuffer packed;
    float[] projected,pq,pa;
    boolean reused;
    void reset(){previous=null;previousTime=0;validCount=0;}
    static boolean valid(float z){return z>=.15f&&z<=8f;}
    static float[] invert(float[] m){float[] r=new float[16];if(!Matrix.invertM(r,0,m,0))throw new IllegalArgumentException("Singular matrix");return r;}
    static float[] uvInverse(float[] m){float det=m[0]*m[4]-m[3]*m[1];if(Math.abs(det)<1e-8)throw new IllegalArgumentException("Singular UV");return new float[]{m[4]/det,-m[1]/det,-m[3]/det,m[0]/det,m[6],m[7]};}
    boolean process(int w,int h,short[] full,short[] raw,byte[] confidence,byte[] luma,long time,float[] view,float[] projection,float[] uv){
        if(w<1||h<1)return false;
        int n=w*h;float dt=(time-previousTime)*1e-9f;
        boolean history=previous!=null&&width==w&&height==h&&dt>0&&dt<.13f;
        float[] oldToNew=new float[16];
        if(history){Matrix.multiplyMM(oldToNew,0,view,0,invert(previousView),0);float translation=(float)Math.sqrt(oldToNew[12]*oldToNew[12]+oldToNew[13]*oldToNew[13]+oldToNew[14]*oldToNew[14]);float cosine=(oldToNew[0]+oldToNew[5]+oldToNew[10]-1)*.5f;history=translation<.2f&&cosine>.966f;}
        if(projected==null||projected.length!=n){projected=new float[n];pq=new float[n];pa=new float[n];}
        Arrays.fill(projected,Float.POSITIVE_INFINITY);Arrays.fill(pq,0);Arrays.fill(pa,0);
        if(history){
            float[] ip=invert(previousProjection),iu=uvInverse(previousUv);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int k=y*w+x;float z=previous[k];if(!valid(z)||age[k]+dt>.12f||quality[k]<.1)continue;
                float u=(x+.5f)/w-iu[4],v=(y+.5f)/h-iu[5];float sx=iu[0]*u+iu[2]*v,sy=iu[1]*u+iu[3]*v;
                float nx=2*sx-1,ny=2*sy-1;float rx=ip[0]*nx+ip[4]*ny+ip[8]+ip[12],ry=ip[1]*nx+ip[5]*ny+ip[9]+ip[13],rz=ip[2]*nx+ip[6]*ny+ip[10]+ip[14];
                if(rz>=0)continue;float factor=-z/rz;rx*=factor;ry*=factor;rz=-z;
                float tx=oldToNew[0]*rx+oldToNew[4]*ry+oldToNew[8]*rz+oldToNew[12],ty=oldToNew[1]*rx+oldToNew[5]*ry+oldToNew[9]*rz+oldToNew[13],tz=oldToNew[2]*rx+oldToNew[6]*ry+oldToNew[10]*rz+oldToNew[14];if(tz>=-.1)continue;
                float cx=projection[0]*tx+projection[4]*ty+projection[8]*tz+projection[12],cy=projection[1]*tx+projection[5]*ty+projection[9]*tz+projection[13],cw=projection[3]*tx+projection[7]*ty+projection[11]*tz+projection[15];
                float a=(cx/cw+1)*.5f,b=(cy/cw+1)*.5f;int xx=(int)Math.floor((uv[0]*a+uv[3]*b+uv[6])*w),yy=(int)Math.floor((uv[1]*a+uv[4]*b+uv[7])*h);
                if(xx<0||xx>=w||yy<0||yy>=h)continue;int j=yy*w+xx;if(-tz<projected[j]){projected[j]=-tz;pq[j]=quality[k];pa[j]=age[k]+dt;}
            }
        }
        float[] current=previous!=null&&previous.length==n?previous:new float[n],q=quality!=null&&quality.length==n?quality:new float[n],ages=age!=null&&age.length==n?age:new float[n];Arrays.fill(current,0);Arrays.fill(q,0);Arrays.fill(ages,0);validCount=0;reused=full==null;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int k=y*w+x;float z=full==null?0:(full[k]&65535)*.001f;
            if(valid(z)){
                float sum=0,weight=0;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int xx=x+dx,yy=y+dy;if(xx<0||xx>=w||yy<0||yy>=h)continue;int j=yy*w+xx;float d=(full[j]&65535)*.001f;
                    if(!valid(d)||Math.abs(d-z)>Math.max(.025,z*.015))continue;
                    if(luma!=null&&Math.abs((luma[j]&255)-(luma[k]&255))>18)continue;
                    float wt=dx==0&&dy==0?4:1;sum+=d*wt;weight+=wt;
                }
                z=sum/weight;q[k]=.75f;
                if(raw!=null&&confidence!=null&&(confidence[k]&255)>=160){float r=(raw[k]&65535)*.001f;if(valid(r)&&Math.abs(r-z)<Math.max(.04f,z*.02f)){z=.7f*z+.3f*r;q[k]=.95f;}else q[k]=.45f;}
                if(history&&valid(projected[k])&&Math.abs(projected[k]-z)<Math.max(.025,z*.015))z=.75f*z+.25f*projected[k];
                current[k]=z;
            }else if(history&&valid(projected[k])&&pa[k]<.12f){current[k]=projected[k];ages[k]=pa[k];q[k]=pq[k]*Math.max(0,1-dt/.12f);}
            if(valid(current[k])&&q[k]>.1)validCount++;
        }
        width=w;height=h;previous=current;quality=q;age=ages;previousTime=time;previousView=view.clone();previousProjection=projection.clone();previousUv=uv.clone();
        if(packed==null||packed.capacity()!=n*4)packed=ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder());packed.clear();
        for(int k=0;k<n;k++){packed.putShort((short)Math.round(current[k]*1000));packed.putShort((short)Math.round(q[k]*255));}packed.flip();
        return validCount>0;
    }
}
