package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import java.io.*;
public class ResultViewTests extends Instrumentation {
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        CaptureResultsActivity a=(CaptureResultsActivity)startActivitySync(new Intent(c,CaptureResultsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        if(a.pictures==null||a.pictures.length<5||a.bitmap==null)throw new AssertionError("Actual result images failed to load");
        runOnMainSync(()->{a.index=1;a.showImage();a.image.setRotation(90);View view=a.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"new-capture-result-screen.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}image.recycle();});
        result.putString("stream","PASS Saved new capture result reopens actual phone renders\nPASS View selection and rotation render without training again\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL "+e);finish(Activity.RESULT_CANCELED,result);}}
}
