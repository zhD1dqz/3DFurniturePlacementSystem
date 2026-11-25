package com.furniture.chairdemo;
import org.json.*;
final class CropBox {
    final float x,y,z,width,height,depth,yaw;
    CropBox(float x,float y,float z,float w,float h,float d,float yaw){
        for(float v:new float[]{x,y,z,w,h,d,yaw})if(!Float.isFinite(v))throw new IllegalArgumentException("裁剪范围无效");
        if(w<.05||h<.05||d<.05||w>20||h>20||d>20)throw new IllegalArgumentException("裁剪范围超出限制");
        this.x=x;this.y=y;this.z=z;width=w;height=h;depth=d;this.yaw=yaw;
    }
    static CropBox fromJson(JSONObject j)throws JSONException{JSONArray c=j.getJSONArray("center"),s=j.getJSONArray("size");return new CropBox((float)c.getDouble(0),(float)c.getDouble(1),(float)c.getDouble(2),(float)s.getDouble(0),(float)s.getDouble(1),(float)s.getDouble(2),(float)j.getDouble("yaw_rad"));}
    boolean contains(float px,float py,float pz){double c=Math.cos(yaw),s=Math.sin(yaw),dx=px-x,dz=pz-z;return Math.abs(c*dx+s*dz)<=width/2&&Math.abs(py-y)<=height/2&&Math.abs(-s*dx+c*dz)<=depth/2;}
    JSONObject json()throws JSONException{return new JSONObject().put("center",CaptureData.array(new float[]{x,y,z})).put("size",CaptureData.array(new float[]{width,height,depth})).put("yaw_rad",yaw);}
    static float[] select(float[] source,CropBox box){int n=0;for(int k=0;k<source.length;k+=37)if(box.contains(source[k],source[k+1],source[k+2]))n++;float[] result=new float[n*37];n=0;for(int k=0;k<source.length;k+=37)if(box.contains(source[k],source[k+1],source[k+2])){System.arraycopy(source,k,result,n*37,37);n++;}return result;}
    float[] lines(){float[] points=new float[24];double c=Math.cos(yaw),s=Math.sin(yaw);for(int i=0;i<8;i++){float lx=(i%2==0?-1:1)*width/2,lz=((i/2)%2==0?-1:1)*depth/2;points[i*3]=(float)(x+c*lx-s*lz);points[i*3+1]=y+(i<4?-1:1)*height/2;points[i*3+2]=(float)(z+s*lx+c*lz);}int[] edge={0,1,1,3,3,2,2,0,4,5,5,7,7,6,6,4,0,4,1,5,2,6,3,7};float[] result=new float[72];for(int i=0;i<24;i++)System.arraycopy(points,edge[i]*3,result,i*3,3);return result;}
}
