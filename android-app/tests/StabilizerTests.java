package com.furniture.chairdemo;
import android.opengl.Matrix;
import java.util.Arrays;

final class StabilizerTests {
    static void run(SmokeTests test){
        float[] v=new float[16],p=new float[16],uv={1,0,0,0,1,0,0,0,1};Matrix.setIdentityM(v,0);Matrix.perspectiveM(p,0,60,1,.03f,30);
        short[] frame=new short[1024];Arrays.fill(frame,(short)2000);DepthStabilizer d=new DepthStabilizer();long now=1000000000L;
        test.check(d.process(32,32,frame,null,null,null,now,v,p,uv),"Depth processing accepts valid metric frame");
        for(int i=1;i<=8;i++){Arrays.fill(frame,(short)(i%2==0?1985:2015));d.process(32,32,frame,null,null,null,now+i*33000000L,v,p,uv);}
        test.check(Math.abs(d.previous[500]-2)<.014,"Motion-compensated history reduces static jitter");
        Arrays.fill(frame,(short)1000);now+=9*33000000L;d.process(32,32,frame,null,null,null,now,v,p,uv);test.check(Math.abs(d.previous[500]-1)<.001,"New foreground rejects old background history");
        test.check(d.process(32,32,null,null,null,null,now+20000000,v,p,uv),"Brief depth gap reuses reprojected history");
        test.check(!d.process(32,32,null,null,null,null,now+180000000,v,p,uv),"Expired depth history is discarded");
        d.reset();Arrays.fill(frame,(short)0);frame[16*32+16]=2000;now+=1000000000L;d.process(32,32,frame,null,null,null,now,v,p,uv);
        float[] moved=v.clone();moved[12]=-.14433757f;d.process(32,32,null,null,null,null,now+20000000,moved,p,uv);
        test.check(d.previous[16*32+14]>1.9f&&d.previous[16*32+16]==0,"Camera motion reprojects history instead of retaining screen mask");
        d.reset();Arrays.fill(frame,(short)1000);for(int y=0;y<32;y++)for(int x=16;x<32;x++)frame[y*32+x]=3000;d.process(32,32,frame,null,null,null,now+2000000000L,v,p,uv);
        test.check(d.previous[16*32+15]<1.01&&d.previous[16*32+16]>2.99,"Spatial filter preserves sharp depth edge");
        float[] jumped=v.clone();jumped[12]=-.4f;test.check(!d.process(32,32,null,null,null,null,now+2020000000L,jumped,p,uv),"Large camera motion resets depth history");
    }
}
