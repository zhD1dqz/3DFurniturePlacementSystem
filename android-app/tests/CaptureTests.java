package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** Safe real-device tests: synthetic capture data lives only in a unique cache folder. */
public class CaptureTests extends SmokeTests {
    @Override public void onStart(){Bundle result=new Bundle();try{
        check(CaptureData.freshImage(395197133473262L,0,395197133161693L,0),"SDK-paired image with observed device clock offset is accepted");
        check(!CaptureData.freshImage(200,100,300,300)&&!CaptureData.freshImage(100,100,400,300)&&!CaptureData.freshImage(0,0,0,0),"Repeated frame, repeated image and zero timestamps are rejected independently");
        ByteBuffer y=ByteBuffer.wrap(new byte[]{99,1,2,0,0,3,4,0,0});y.position(1);
        ByteBuffer u=ByteBuffer.wrap(new byte[]{99,10,0});u.position(1);ByteBuffer v=ByteBuffer.wrap(new byte[]{99,20,0});v.position(1);
        byte[] packed=CaptureData.nv21(2,2,new ByteBuffer[]{y,u,v},new int[]{4,2,2},new int[]{1,2,2});
        check(Arrays.equals(packed,new byte[]{1,2,3,4,20,10}),"Padded YUV planes with nonzero positions convert to NV21 VU correctly");
        byte[] planar=CaptureData.nv21(2,2,new ByteBuffer[]{ByteBuffer.wrap(new byte[]{1,2,3,4}),ByteBuffer.wrap(new byte[]{10}),ByteBuffer.wrap(new byte[]{20})},new int[]{2,1,1},new int[]{1,1,1});
        check(Arrays.equals(planar,packed),"Planar and interleaved chroma strides produce the same image");
        FloatBuffer mixed=FloatBuffer.wrap(new float[]{99,1,2,3,.8f,Float.NaN,2,3,.9f,1,Float.POSITIVE_INFINITY,3,.8f,1,2,3,Float.NaN,1,2,3,2,4,5,6,.5f,777});mixed.position(1);
        JSONObject filtered=CaptureData.pointCloud(mixed,100);JSONArray points=filtered.getJSONArray("point_cloud_world_xyzc");
        check(points.length()==8&&points.getDouble(0)==1&&points.getDouble(4)==4,"Point cloud preserves valid complete records and buffer offset");
        check(filtered.getInt("point_cloud_invalid_points")==4&&filtered.getInt("point_cloud_trailing_values")==1,"NaN, infinity, invalid confidence and incomplete point records are handled");
        check(mixed.position()==1&&new JSONObject(filtered.toString()).getJSONArray("point_cloud_world_xyzc").length()==8,"Filtered point cloud serializes to real Android JSON without consuming buffer");
        check(CaptureData.pointCloud(FloatBuffer.wrap(new float[]{Float.NaN,0,0,1}),1).getJSONArray("point_cloud_world_xyzc").length()==0,"An entirely invalid cloud remains an empty optional observation");
        boolean rejectedPose=false;try{CaptureData.array(new float[]{Float.NaN});}catch(JSONException expected){rejectedPose=true;}check(rejectedPose,"Invalid essential camera data is still rejected");
        File sequence=new File(getTargetContext().getCacheDir(),"cloud-sequence-"+System.nanoTime());sequence.mkdirs();
        for(int i=0;i<30;i++){
            JSONObject record=new JSONObject().put("image","image-"+i+".jpg");
            JSONObject cloud=CaptureData.pointCloud(FloatBuffer.wrap(i<10?new float[]{1,2,3,.8f}:new float[]{Float.NaN,2,3,.8f,1,2,3,.5f}),i);
            record.put("point_cloud_world_xyzc",cloud.getJSONArray("point_cloud_world_xyzc"));CaptureData.atomic(new File(sequence,"frame-"+i+".json"),record);
        }
        check(sequence.listFiles((dir,name)->name.endsWith(".json")).length==30&&ModelStore.json(new File(sequence,"frame-29.json")).getJSONArray("point_cloud_world_xyzc").length()==4,"Thirty metadata commits continue when invalid cloud points appear after frame ten");
        float[] gl={1,0,0,0,0,1,0,0,0,0,1,0,2,3,4,1};float[] cv=CaptureData.cvPose(gl);
        check(cv[0]==1&&cv[5]==-1&&cv[10]==-1&&cv[12]==2&&cv[13]==3&&cv[14]==4,"Physical pose conversion flips camera Y/Z and preserves world translation");
        // Pixel right/below center in CV must correspond to right/down in OpenGL world.
        float x=.2f,yd=.1f,z=2;float wx=cv[0]*x+cv[4]*yd+cv[8]*z+cv[12],wy=cv[1]*x+cv[5]*yd+cv[9]*z+cv[13],wz=cv[2]*x+cv[6]*yd+cv[10]*z+cv[14];
        check(Math.abs(wx-2.2)<1e-5&&Math.abs(wy-2.9)<1e-5&&Math.abs(wz-2)<1e-5,"Off-center image ray maps to expected world location without display rotation");
        check(!CaptureData.moved(gl,gl.clone()),"Stationary frames are not accepted as new views");float[] shifted=gl.clone();shifted[12]+=.04f;check(CaptureData.moved(gl,shifted),"Four-centimeter camera translation accepts a new view");
        File fixture=new File(getTargetContext().getCacheDir(),"capture-test-"+System.nanoTime());File task=new File(fixture,"captures/scan-test");task.mkdirs();
        CaptureData.atomic(new File(task,"capture.json"),new JSONObject().put("state","recording").put("id","scan-test"));
        try(FileOutputStream f=new FileOutputStream(new File(task,"frame-0000.jpg"))){f.write(new byte[]{1,2,3});}
        CaptureData.atomic(new File(task,"frame-0000.json"),new JSONObject().put("image","frame-0000.jpg"));
        CaptureData.atomic(new File(task,"frame-0001.json"),new JSONObject().put("image","missing.jpg"));
        List<JSONObject> list=CaptureData.sessions(fixture);check(list.size()==1&&list.get(0).getInt("committed_frames")==1,"Recovery counts only committed records with existing image files");
        check("interrupted".equals(list.get(0).getString("state")),"Unfinished capture is recovered as interrupted");
        CaptureData.atomic(new File(task,"capture.json"),new JSONObject().put("state","completed").put("id","scan-test"));check("completed".equals(CaptureData.sessions(fixture).get(0).getString("state")),"Completed capture can reopen from disk");
        Context context=getTargetContext();String selected=ModelStore.active(context);Intent intent=new Intent(context,MainActivity.class).putExtra("model_id",selected).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=(MainActivity)startActivitySync(intent);waitForIdleSync();Thread.sleep(900);gl(()->{});
        check(activity.renderer.fatal==null&&selected.equals(activity.activeModelId),"Existing selected model survives scan app update");
        capture("scan-update-preview.png");
        result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",results+"FAIL "+error);finish(Activity.RESULT_CANCELED,result);}}
}
