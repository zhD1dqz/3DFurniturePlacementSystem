package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import java.io.*;
import org.json.*;

public class ComputerResumeTests extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        Context c=getTargetContext();android.content.SharedPreferences prefs=c.getSharedPreferences("computer-processing",0);String task=prefs.getString("local_task","");if(!task.matches("probe-\\d+-1200"))throw new AssertionError("Requires actual completed computer flow");
        String active=ModelStore.active(c),hash=PlyModelImporter.sha(new File(c.getFilesDir(),task+"/integration-result.json"));int count=c.getFilesDir().listFiles((d,n)->n.matches("probe-\\d+-1200")).length;
        startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();ComputerActivity a=(ComputerActivity)startActivitySync(new Intent(c,ComputerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        long deadline=SystemClock.elapsedRealtime()+30000;while(a.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);Thread.sleep(250);
        if(a.busy||a.localTask==null||!task.equals(a.localTask.getName())||!a.open.isEnabled())throw new AssertionError("Existing computer result did not resume");
        if(count!=c.getFilesDir().listFiles((d,n)->n.matches("probe-\\d+-1200")).length||!hash.equals(PlyModelImporter.sha(new File(c.getFilesDir(),task+"/integration-result.json"))))throw new AssertionError("Resume created a duplicate model or modified the result");
        if(!active.equals(ModelStore.active(c)))throw new AssertionError("Resume changed selected furniture");
        if((a.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0)throw new AssertionError("Transfer page may sleep during a job");
        Bitmap screen=getUiAutomation().takeScreenshot();if(screen!=null)try(FileOutputStream stream=new FileOutputStream(new File(c.getExternalFilesDir(null),"computer-result-screen.png"))){screen.compress(Bitmap.CompressFormat.PNG,100,stream);}if(screen!=null)screen.recycle();
        ActivityMonitor monitor=addMonitor(SceneViewerActivity.class.getName(),null,false);runOnMainSync(()->a.open.performClick());Activity viewer=waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);if(!(viewer instanceof SceneViewerActivity)||!ModelStore.json(new File(c.getFilesDir(),task+"/integration-result.json")).getString("model_sha256").equals(((SceneViewerActivity)viewer).sceneId))throw new AssertionError("View result button opened the wrong model");
        CaptureData.atomic(new File(c.getFilesDir(),"computer-resume-test.json"),new JSONObject().put("status","passed").put("task",task).put("no_duplicate_import",true).put("active_selection_preserved",true));out.putString("stream","PASS Computer task resumes over Wi-Fi without retraining or duplicate import\nPASS Downloaded result and selected furniture preserved\nPASS Transfer page remains awake\n");finish(Activity.RESULT_OK,out);
    }catch(Throwable error){out.putString("stream","FAIL "+error);finish(Activity.RESULT_CANCELED,out);}}
}
