package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;
public class CalibrationProbeTests extends Instrumentation {
    Bundle options;
    @Override public void onCreate(Bundle args){options=args;super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{Context c=getTargetContext();String id=options.getString("source_id");android.content.SharedPreferences p=c.getSharedPreferences("model-size-"+id,0);float[] range=GroundCalibration.suggest(GroundCalibration.model(c,id));String ready=GroundCalibration.prepareForRoom(c,id,p.getFloat("depth",0),p.getFloat("width",0),p.getFloat("height",0),p.getString("sizeMode","height"));out.putString("stream","PASS preparation "+new JSONObject().put("id",ready).put("ground",range[0]).put("top",range[1]));finish(Activity.RESULT_OK,out);}catch(Throwable error){out.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,out);}}
}
