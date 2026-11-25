package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Real HTTP to isolated PC fixture; downloads an existing PLY, never trains. */
public class NetworkFunctionTests extends Instrumentation {
    StringBuilder checks=new StringBuilder();ComputerActivity a;String url;SharedPreferences prefs;Map<String,?> backup;
    void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks.append("PASS ").append(text).append('\n');}
    @Override public void onCreate(Bundle args){url=args.getString("url","http://10.0.2.2:18268");super.onCreate(args);start();}
    void idle()throws Exception{long end=SystemClock.uptimeMillis()+30000;while(a.busy&&SystemClock.uptimeMillis()<end)Thread.sleep(50);check(!a.busy,"Network operation terminates");waitForIdleSync();}
    void restore(){if(prefs==null||backup==null)return;SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> v:backup.entrySet()){Object x=v.getValue();String k=v.getKey();if(x instanceof String)e.putString(k,(String)x);else if(x instanceof Boolean)e.putBoolean(k,(Boolean)x);else if(x instanceof Integer)e.putInt(k,(Integer)x);else if(x instanceof Long)e.putLong(k,(Long)x);else if(x instanceof Float)e.putFloat(k,(Float)x);}e.commit();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        prefs=getTargetContext().getSharedPreferences("computer-processing",0);backup=new HashMap<>(prefs.getAll());prefs.edit().clear().commit();
        a=(ComputerActivity)startActivitySync(new Intent(getTargetContext(),ComputerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        prefs=a.prefs();
        runOnMainSync(()->a.pair(url,"12345678"));idle();check(prefs.contains("token"),"Pairs with actual HTTP fixture: "+a.status.getText());
        runOnMainSync(()->a.checkConnection());idle();check(a.status.getText().toString().contains("连接正常"),"Connection detector verifies health, server identity and auth: "+a.status.getText());
        JSONArray jobs=a.request("GET","/api/jobs",null,true).getJSONArray("jobs");check(jobs.length()==2,"History contains completed and interrupted fixture jobs");
        runOnMainSync(()->a.history());idle();Thread.sleep(300);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
        String id=String.join("",Collections.nCopies(32,"d"));JSONObject job=a.request("GET","/api/jobs/"+id,null,true);
        prefs.edit().putString("job_id",id).putString("job_capture",job.getString("capture_id")).commit();a.captureId=job.getString("capture_id");
        runOnMainSync(()->a.resumeJob());idle();check(a.localTask!=null&&new File(a.localTask,"integration-result.json").isFile(),"Completed job downloads and imports a real Gaussian PLY");
        String first=a.localTask.getName(),model=ModelStore.json(new File(a.localTask,"integration-result.json")).getString("model");String hash=PlyModelImporter.sha(new File(a.localTask,model));
        runOnMainSync(()->a.resumeJob());idle();check(first.equals(a.localTask.getName()),"Repeated result query reuses the same local task");
        prefs.edit().remove("local_task").commit();a.localTask=null;runOnMainSync(()->a.resumeJob());idle();check(first.equals(a.localTask.getName()),"Recovers imported result after preference mapping is lost without duplicating it");
        JSONObject invalid=new JSONObject(job.toString()).put("model_sha256",String.join("",Collections.nCopies(64,"0")));boolean rejected=false;try{a.download(invalid);}catch(Exception e){rejected=true;}check(rejected,"Wrong model hash is rejected");
        File[] pending=a.getFilesDir().listFiles((d,n)->n.startsWith(".computer-pending-"));check(pending!=null&&pending.length==0,"Failed download cleans only its pending folder");
        check(hash.equals(PlyModelImporter.sha(new File(a.localTask,model))),"Failed download preserves earlier valid model");
        runOnMainSync(()->{a.closed=true;a.finish();});restore();
        out.putString("stream",checks+"Scope: real emulator HTTP and PLY import using pre-existing sample; not new training or physical AR.\n");finish(Activity.RESULT_OK,out);
    }catch(Throwable e){restore();out.putString("stream",checks+"FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}}
}
