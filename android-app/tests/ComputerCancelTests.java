package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.util.*;
import org.json.*;

/** Actual upload/cancel; restores the completed user's computer-task selection afterwards. */
public class ComputerCancelTests extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();Context c=getTargetContext();android.content.SharedPreferences prefs=c.getSharedPreferences("computer-processing",0);Map<String,?> previous=new HashMap<>(prefs.getAll());boolean passed=false;try{
        String task=prefs.getString("local_task","");if(!task.matches("probe-\\d+-1200"))throw new AssertionError("Requires completed computer model");File prior=new File(c.getFilesDir(),task+"/integration-result.json");String checksum=PlyModelImporter.sha(prior);String capture=prefs.getString("job_capture","");
        startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();ComputerActivity a=(ComputerActivity)startActivitySync(new Intent(c,ComputerActivity.class).putExtra("capture_id",capture).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();long deadline=SystemClock.elapsedRealtime()+20000;while(a.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
        runOnMainSync(()->a.begin());String job="";JSONObject state=null;deadline=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<deadline){String current=prefs.getString("job_id","");if(!current.equals(previous.get("job_id"))&&current.matches("[a-f0-9]{32}")){try{state=a.request("GET","/api/jobs/"+current,null,true);if("running".equals(state.optString("status"))){job=current;break;}}catch(Exception ignored){}}Thread.sleep(200);}
        if(job.isEmpty()||!a.cancel.isEnabled())throw new AssertionError("Actual running job has no enabled cancel control");runOnMainSync(()->a.cancel.performClick());Thread.sleep(300);boolean clicked=false;AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("取消处理"))if(node.isClickable()&&"取消处理".contentEquals(node.getText())){clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);break;}if(!clicked)throw new AssertionError("Cancel confirmation unavailable");
        deadline=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<deadline){state=a.request("GET","/api/jobs/"+job,null,true);if("cancelled".equals(state.optString("status")))break;Thread.sleep(200);}if(!"cancelled".equals(state.optString("status")))throw new AssertionError("Desktop worker did not cancel");
        if(!checksum.equals(PlyModelImporter.sha(prior)))throw new AssertionError("Cancellation altered previous completed model");CaptureData.atomic(new File(c.getFilesDir(),"computer-cancel-test.json"),new JSONObject().put("status","passed").put("cancelled_job",job).put("previous_model_preserved",true));out.putString("stream","PASS Actual Wi-Fi upload and cancel confirmation stop desktop processing\nPASS Previously downloaded model remains intact\n");passed=true;
    }catch(Throwable error){out.putString("stream","FAIL "+error);}finally{android.content.SharedPreferences.Editor editor=prefs.edit().clear();for(Map.Entry<String,?> entry:previous.entrySet())if(entry.getValue() instanceof String)editor.putString(entry.getKey(),(String)entry.getValue());editor.commit();}finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);}
}
