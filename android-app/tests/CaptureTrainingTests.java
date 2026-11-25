package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

public class CaptureTrainingTests extends Instrumentation {
    Bundle options;
    @Override public void onCreate(Bundle args){super.onCreate(args);options=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        File latest=new File(context.getFilesDir(),"latest-output.txt");String before=latest.exists()?ModelStore.text(new FileInputStream(latest)).trim():"";
        final String capture=options.getString("capture_id");if(capture==null||!capture.matches("scan-\\d+-[a-f0-9]{8}"))throw new AssertionError("Explicit capture_id is required; never fall back to old data");
        runOnMainSync(()->context.startActivity(new Intent().setClassName(context.getPackageName(),"com.furniture.reconstructionprobe.MainActivity").putExtra("capture_id",capture).putExtra("auto_generate",true).putExtra("probe_config_b64",options.getString("config")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        long start=SystemClock.elapsedRealtime();JSONObject done=null;String output="";
        long timeout=Long.parseLong(options.getString("test_timeout_ms","3600000"));
        while(SystemClock.elapsedRealtime()-start<timeout){if(latest.exists()){String name=ModelStore.text(new FileInputStream(latest)).trim();if(!before.equals(name)&&name.matches("probe-\\d+-1200")){output=name;File path=new File(context.getFilesDir(),name+"/integration-result.json");if(path.exists()){done=ModelStore.json(path);break;}}}Thread.sleep(500);}
        if(done==null||!"completed".equals(done.optString("phase")))throw new AssertionError("New capture training incomplete: "+done);
        if(!capture.equals(done.optString("capture_id"))||done.getInt("completed_steps")>done.getInt("requested_steps")||done.getInt("completed_steps")<5)throw new AssertionError("Wrong capture or invalid step count");
        if(done.getInt("completed_steps")!=done.getInt("requested_steps")&&!done.optBoolean("stopped_for_time_budget"))throw new AssertionError("Unexplained early stop");
        if(done.getLong("generation_and_prepare_ms")>=600000)throw new AssertionError("Ten-minute generation limit exceeded");
        String scene=SceneStore.prepare(context,new File(context.getFilesDir(),output));SceneStore.load(context,scene);
        JSONObject report=new JSONObject().put("status","passed").put("output",output).put("result",done);CaptureData.atomic(new File(context.getFilesDir(),"capture-training-test.json"),report);
        result.putString("stream","PASS Selected phone capture generates a valid model within ten minutes\n"+report);finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+error);finish(Activity.RESULT_CANCELED,result);}}
}
