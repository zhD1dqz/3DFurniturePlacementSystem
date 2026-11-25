package com.furniture.chairdemo;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** Unrotated CPU image contract. No display pose or display crop enters training data. */
final class CaptureData {
    static boolean freshImage(long frame,long lastFrame,long image,long lastImage){return frame>lastFrame&&image>lastImage&&frame>0&&image>0;}
    static byte[] nv21(int w,int h,ByteBuffer[] planes,int[] row,int[] pixel){
        if(w<=0||h<=0||(w&1)!=0||(h&1)!=0)throw new IllegalArgumentException("Even image dimensions required");
        byte[] output=new byte[w*h*3/2];int at=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)output[at++]=planes[0].get(planes[0].position()+y*row[0]+x*pixel[0]);
        for(int y=0;y<h/2;y++)for(int x=0;x<w/2;x++){
            output[at++]=planes[2].get(planes[2].position()+y*row[2]+x*pixel[2]);
            output[at++]=planes[1].get(planes[1].position()+y*row[1]+x*pixel[1]);
        }return output;
    }
    static JSONArray array(float[] values)throws JSONException{JSONArray a=new JSONArray();for(float v:values){if(!Float.isFinite(v))throw new JSONException("Non-finite camera data");a.put(v);}return a;}
    // Sparse geometry is optional. Reject entire invalid XY Z confidence records,
    // never replace NaN coordinates with invented zero positions.
    static JSONObject pointCloud(FloatBuffer points,long timestamp)throws JSONException{
        JSONArray valid=new JSONArray();int rejected=0,records=Math.min(points.remaining()/4,4096),base=points.position();
        for(int i=0;i<records;i++){
            float x=points.get(base+4*i),y=points.get(base+4*i+1),z=points.get(base+4*i+2),confidence=points.get(base+4*i+3);
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(confidence)||confidence<0||confidence>1){rejected++;continue;}
            valid.put(x);valid.put(y);valid.put(z);valid.put(confidence);
        }
        return new JSONObject().put("point_cloud_world_xyzc",valid).put("point_cloud_timestamp_ns",timestamp)
            .put("point_cloud_status",rejected>0?"filtered":"available").put("point_cloud_invalid_points",rejected)
            .put("point_cloud_trailing_values",points.remaining()%4).put("point_cloud_capped_points",Math.max(0,points.remaining()/4-records));
    }
    // ARCore/OpenGL c2w -> OpenCV c2w: local Y and Z axes change sign. Column major.
    static float[] cvPose(float[] gl){float[] cv=gl.clone();for(int i=4;i<12;i++)cv[i]=-cv[i];return cv;}
    static boolean moved(float[] a,float[] b){
        if(a==null)return true;double d=0;for(int i=12;i<15;i++)d+=(a[i]-b[i])*(a[i]-b[i]);
        double trace=0;for(int col=0;col<3;col++)for(int row=0;row<3;row++)trace+=a[col*4+row]*b[col*4+row];
        return d>=.03*.03||Math.acos(Math.max(-1,Math.min(1,(trace-1)/2)))>=Math.toRadians(5);
    }
    static void atomic(File target,JSONObject value)throws Exception{
        File temp=new File(target.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("Could not commit capture metadata");
    }
    static List<JSONObject> sessions(File files){
        List<JSONObject> result=new ArrayList<>();File[] dirs=new File(files,"captures").listFiles();if(dirs==null)return result;
        Arrays.sort(dirs,(a,b)->b.getName().compareTo(a.getName()));
        for(File dir:dirs)try{
            JSONObject data=ModelStore.json(new File(dir,"capture.json"));int committed=0;File[] entries=dir.listFiles();
            if(entries!=null)for(File file:entries)if(file.getName().matches("frame-\\d{4}\\.json")){
                JSONObject frame=ModelStore.json(file);File jpeg=new File(dir,frame.getString("image"));if(jpeg.isFile()&&jpeg.length()>0)committed++;
            }
            data.put("committed_frames",committed);if("recording".equals(data.optString("state")))data.put("state","interrupted");result.add(data);
        }catch(Exception ignored){}return result;
    }
}
