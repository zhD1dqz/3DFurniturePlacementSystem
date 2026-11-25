package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;

/** Cancel actual GPU training through the same action as the confirmation button. */
public class GenerationCancelTests extends Instrumentation {
    Bundle options;
    @Override public void onCreate(Bundle args){super.onCreate(args);options=args;start();}
    void clickCancel()throws Exception{
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&"com.furniture.chairdemo".contentEquals(root.getPackageName())){
            for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("取消生成"))if(node.isClickable()&&node.isEnabled()&&"取消生成".contentEquals(node.getText())&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
        }Thread.sleep(200);}throw new AssertionError("App cancel control unavailable");
    }
    @Override public void onStart(){Bundle out=new Bundle();try{
        Context c=getTargetContext();String capture=options.getString("capture_id");if(capture==null||!capture.matches("scan-\\d+-[a-f0-9]{8}"))throw new AssertionError("Explicit capture_id required");
        File previous=SceneStore.latestTask(c),manifest=new File(c.getFilesDir(),"captures/"+capture+"/capture.json");String before=PlyModelImporter.sha(manifest);
        startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        String prior=ModelStore.text(new FileInputStream(new File(c.getFilesDir(),"latest-output.txt"))).trim();
        runOnMainSync(()->c.startActivity(new Intent().setClassName(c.getPackageName(),"com.furniture.reconstructionprobe.MainActivity").putExtra("capture_id",capture).putExtra("auto_generate",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        File task=null;long deadline=SystemClock.elapsedRealtime()+180000;
        while(SystemClock.elapsedRealtime()<deadline){String name=ModelStore.text(new FileInputStream(new File(c.getFilesDir(),"latest-output.txt"))).trim();if(!name.equals(prior)&&name.matches("probe-\\d+-1200")){File candidate=new File(c.getFilesDir(),name);try{JSONObject p=ModelStore.json(new File(candidate,"progress.json"));if("training".equals(p.optString("phase"))&&p.optInt("iteration")>=10){task=candidate;break;}}catch(IOException ignored){}}Thread.sleep(300);}
        if(task==null)throw new AssertionError("No actual GPU training to cancel");
        clickCancel();Thread.sleep(500);clickCancel();
        JSONObject done=null;deadline=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<deadline){File f=new File(task,"integration-result.json");if(f.exists()){done=ModelStore.json(f);break;}Thread.sleep(300);}
        if(done==null||!"cancelled".equals(done.optString("phase")))throw new AssertionError("Cancellation failed: "+done);
        if(!previous.equals(SceneStore.latestTask(c))||!before.equals(PlyModelImporter.sha(manifest)))throw new AssertionError("Cancellation changed saved data or published an unfinished result");
        JSONObject report=new JSONObject().put("status","passed").put("cancelled_task",task.getName()).put("previous_completed_task",previous.getName()).put("capture_preserved",true).put("result",done);
        CaptureData.atomic(new File(c.getFilesDir(),"generation-cancel-test.json"),report);out.putString("stream","PASS Real GPU generation cancels and preserves capture and completed model\n"+report);finish(Activity.RESULT_OK,out);
    }catch(Throwable error){out.putString("stream","FAIL "+error);finish(Activity.RESULT_CANCELED,out);}}
}
