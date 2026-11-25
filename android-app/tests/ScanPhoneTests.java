package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import android.media.Image;
import com.google.ar.core.Frame;
import java.nio.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

/** Camera-only probe in cache: does not publish non-tracking poses as capture data. */
public class ScanPhoneTests extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        final ScanActivity scan=(ScanActivity)startActivitySync(new Intent(getTargetContext(),ScanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline=SystemClock.elapsedRealtime()+15000;while(scan.session==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
        if(scan.session==null)throw new AssertionError("Camera unavailable");
        File folder=new File(getTargetContext().getCacheDir(),"sdk-pair-probe-"+System.currentTimeMillis());folder.mkdirs();
        JSONArray samples=new JSONArray();long[] previous={0,0};String[] failure={null};
        for(int n=0;n<12;n++){
            CountDownLatch done=new CountDownLatch(1);final int index=n;
            scan.surface.queueEvent(()->{try{
                Frame frame=scan.session.update();try(Image image=frame.acquireCameraImage()){
                    long ft=frame.getTimestamp(),it=image.getTimestamp(),at=frame.getAndroidCameraTimestamp();
                    if(!CaptureData.freshImage(ft,previous[0],it,previous[1]))return;
                    Image.Plane[] planes=image.getPlanes();ByteBuffer[] bytes=new ByteBuffer[3];int[] row=new int[3],pixel=new int[3];for(int k=0;k<3;k++){bytes[k]=planes[k].getBuffer();row[k]=planes[k].getRowStride();pixel[k]=planes[k].getPixelStride();}
                    int w=image.getWidth(),h=image.getHeight();byte[] nv=CaptureData.nv21(w,h,bytes,row,pixel);
                    int[] dimensions=frame.getCamera().getImageIntrinsics().getImageDimensions();
                    android.util.Size selected=scan.session.getCameraConfig().getImageSize();
                    if(w!=selected.getWidth()||h!=selected.getHeight()||dimensions[0]!=w||dimensions[1]!=h)throw new AssertionError("CPU image, selected config and pose intrinsics differ");
                    String name="image-"+index+".jpg";try(FileOutputStream out=new FileOutputStream(new File(folder,name))){if(!new YuvImage(nv,android.graphics.ImageFormat.NV21,w,h,null).compressToJpeg(new Rect(0,0,w,h),92,out))throw new IOException("JPEG failed");}
                    samples.put(new JSONObject().put("width",w).put("height",h).put("frame_timestamp_ns",ft).put("image_timestamp_ns",it).put("android_camera_timestamp_ns",at).put("offset_ns",it-at).put("image",name).put("tracking",frame.getCamera().getTrackingState().toString()));previous[0]=ft;previous[1]=it;
                }
            }catch(com.google.ar.core.exceptions.NotYetAvailableException ignored){}catch(Throwable error){failure[0]=error.toString();}finally{done.countDown();}});
            if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("Camera queue timeout");Thread.sleep(150);
        }
        if(failure[0]!=null)throw new AssertionError(failure[0]);if(samples.length()<4)throw new AssertionError("Too few unique camera images: "+samples.length());
        JSONObject report=new JSONObject().put("status","passed").put("scope","Real SDK image acquisition, duplicate gate and JPEG; not tracking/pose accuracy verification").put("probe_cache_path",folder.getAbsolutePath()).put("samples",samples);
        CaptureData.atomic(new File(getTargetContext().getFilesDir(),"sdk-pair-probe.json"),report);
        result.putString("stream","PASS Actual SDK pairing and image freshness accepted "+samples.length()+" unique phone images\nPASS Actual YUV to JPEG save succeeded\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+error);finish(Activity.RESULT_CANCELED,result);}}
}
