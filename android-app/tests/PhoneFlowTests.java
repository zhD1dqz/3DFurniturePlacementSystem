package com.furniture.chairdemo;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

/** Real-phone flow only. Does not edit the user's original measured dimensions. */
public class PhoneFlowTests extends SmokeTests {
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();try{
        Context context=getTargetContext();
        android.content.SharedPreferences old=context.getSharedPreferences(MainActivity.class.getSimpleName(),0);
        boolean hadHeight=old.contains("height");float priorHeight=old.getFloat("height",1);boolean priorConfirmed=old.getBoolean("dimensionsConfirmed",false);
        Intent preview=new Intent(context,MainActivity.class).putExtra("model_id",ModelStore.BUILTIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity=(MainActivity)startActivitySync(preview);waitForIdleSync();Thread.sleep(700);gl(()->{});
        check(activity.renderer.fatal==null&&activity.renderer.count==9880,"Original model still loads after app update");
        if(hadHeight)check(Math.abs(activity.requestedHeight-priorHeight)<.0001&&activity.dimensionsConfirmed==priorConfirmed,"Original measured dimensions and confirmation survive update");
        final float height=activity.requestedHeight;report.put("reference_height_m",height);
        File latest=new File(context.getFilesDir(),"latest-output.txt");String previous=latest.isFile()?ModelStore.text(new FileInputStream(latest)):"";
        ActivityMonitor readyMonitor=addMonitor(MainActivity.class.getName(),null,false);
        runOnMainSync(()->{
            Intent train=new Intent().setClassName(context.getPackageName(),"com.furniture.reconstructionprobe.MainActivity").putExtra("auto_generate",true).putExtra("reference_height_m",height).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(train);
        });
        long started=SystemClock.elapsedRealtime();String output=null;JSONObject completion=null;
        while(SystemClock.elapsedRealtime()-started<183000){
            if(latest.isFile()){
                String name=ModelStore.text(new FileInputStream(latest)).trim();
                if(!name.equals(previous)&&name.matches("probe-\\d+-1200")){
                    output=name;File done=new File(context.getFilesDir(),name+"/integration-result.json");
                    if(done.isFile())try{completion=ModelStore.json(done);break;}catch(JSONException ignored){}
                    File progress=new File(context.getFilesDir(),name+"/progress.json");
                    if(progress.isFile())try{JSONObject state=ModelStore.json(progress);if("timed_out".equals(state.optString("phase")))throw new AssertionError("Generation exceeded the 180 second budget");}catch(JSONException ignored){}
                }
            }
            Thread.sleep(500);
        }
        check(completion!=null&&completion.optBoolean("handoff_ready")&&"completed".equals(completion.optString("phase")),"Phone generation, crop and private save finish successfully");
        check(completion.getLong("generation_and_import_ms")<180000,"Generation and model preparation stay within three minutes");
        String id=completion.getString("model_id");report.put("model_id",id);report.put("task_output",output);report.put("generation",completion);
        MainActivity loaded=(MainActivity)waitForMonitorWithTimeout(readyMonitor,12000);removeMonitor(readyMonitor);
        check(loaded!=null,"Generation automatically opens preview in the same app");activity=loaded;waitForIdleSync();Thread.sleep(800);gl(()->{});
        JSONObject meta=ModelStore.metadata(context,id);report.put("stored_model",meta);
        check(id.equals(activity.activeModelId)&&activity.renderer.count==meta.getInt("gaussians")&&activity.renderer.fatal==null,"Renderer loads the newly generated private file");
        check(activity.renderer.count!=9880&&activity.renderer.count>=100,"Displayed model is the new extraction, not the built-in model");
        check(!activity.dimensionsConfirmed&&Math.abs(activity.requestedHeight-height)<.0001,"New model preserves the same-capture height hint and requests confirmation");
        capture("phone-generated-preview.png");
        MainActivity builtin=LibraryTests.launch(this,ModelStore.BUILTIN);
        check(Math.abs(builtin.requestedHeight-height)<.0001&&builtin.dimensionsConfirmed==priorConfirmed,"Switching back keeps the original measured dimensions");
        MainActivity reopened=LibraryTests.launch(this,id);
        check(reopened.renderer.fatal==null&&reopened.renderer.count==meta.getInt("gaussians"),"Saved generated model can reopen without training again");
        check(id.equals(ModelStore.active(context)),"Generated model selection is saved on the phone");
        report.put("status","passed");report.put("passed_checks",results.toString());ModelStore.write(new File(context.getFilesDir(),"phone-flow-report.json"),report);
        result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable error){try{report.put("status","failed");report.put("error",error.toString());ModelStore.write(new File(getTargetContext().getFilesDir(),"phone-flow-report.json"),report);}catch(Exception ignored){}result.putString("stream",results+"FAIL "+error);finish(Activity.RESULT_CANCELED,result);}}
}
