package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

/** Real Wi-Fi transfer and, in full mode, actual desktop reconstruction and phone import. */
public class ComputerFlowTests extends Instrumentation {
    Bundle options;
    @Override public void onCreate(Bundle args){super.onCreate(args);options=args;start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        Context c=getTargetContext();String id=options.getString("capture_id"),url=options.getString("url"),code=options.getString("pair_code");
        if(id==null||!id.matches("scan-\\d+-[a-f0-9]{8}")||url==null||code==null)throw new AssertionError("Explicit capture and computer connection required");
        String prior=ModelStore.active(c);startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        ComputerActivity a=(ComputerActivity)startActivitySync(new Intent(c,ComputerActivity.class).putExtra("capture_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        runOnMainSync(()->a.pair(url,code));long deadline=SystemClock.elapsedRealtime()+45000;while(a.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(200);
        if(a.busy||!url.equals(a.prefs().getString("url",""))||!a.prefs().contains("token"))throw new AssertionError("Phone could not pair with LAN computer: "+a.status.getText());
        JSONObject health=a.request("GET","/api/health",null,true);if(health.getInt("protocol")!=1)throw new AssertionError("Wrong server protocol");
        for(String bad:new String[]{"http://8.8.8.8:8268","http://192.168.3.218:8268/redirect","http://user@192.168.3.218:8268"}){boolean rejected=false;try{ComputerActivity.normalizeUrl(bad);}catch(Exception expected){rejected=true;}if(!rejected)throw new AssertionError("Unsafe endpoint accepted");}
        if("true".equals(options.getString("network_only"))){out.putString("stream","PASS Actual phone Wi-Fi health and pairing; non-LAN/credential URLs rejected\n");finish(Activity.RESULT_OK,out);return;}
        runOnMainSync(()->a.begin());deadline=SystemClock.elapsedRealtime()+720000;
        while(a.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(500);
        if(a.localTask==null)throw new AssertionError("Computer flow did not import a model: "+a.status.getText());
        JSONObject done=ModelStore.json(new File(a.localTask,"integration-result.json"));if(!"completed".equals(done.optString("phase"))||!"computer".equals(done.optString("source"))||!id.equals(done.optString("capture_id")))throw new AssertionError("Wrong model provenance");
        String scene=SceneStore.prepare(c,a.localTask);JSONObject packed=SceneStore.load(c,scene);if(done.getInt("gaussians")!=packed.getInt("gaussians"))throw new AssertionError("Phone model count differs from desktop result");
        if(!prior.equals(ModelStore.active(c)))throw new AssertionError("Computer generation replaced user's selected furniture");
        JSONObject report=new JSONObject().put("status","passed").put("task",a.localTask.getName()).put("scene_id",scene).put("result",done).put("scope","Real phone Wi-Fi upload; actual desktop COLMAP/Brush training; checksum download and packed model validation; no USB file transfer");
        CaptureData.atomic(new File(c.getFilesDir(),"computer-flow-test.json"),report);out.putString("stream","PASS Actual phone-to-computer reconstruction and downloaded Gaussian model import\n"+report);finish(Activity.RESULT_OK,out);
    }catch(Throwable error){out.putString("stream","FAIL "+error);finish(Activity.RESULT_CANCELED,out);}}
}
