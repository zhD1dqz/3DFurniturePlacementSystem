package com.furniture.chairdemo;

import android.content.Context;
import android.content.Intent;
import java.io.*;
import org.json.*;

/** Run only on the isolated emulator: includes deliberate corruption tests. */
final class LibraryTests {
    static MainActivity launch(SmokeTests test,String id)throws Exception{
        Intent intent=new Intent(test.getTargetContext(),MainActivity.class).putExtra("model_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        MainActivity activity=(MainActivity)test.startActivitySync(intent);test.waitForIdleSync();Thread.sleep(700);test.activity=activity;test.gl(()->{});return activity;
    }
    static void copy(InputStream input,File target)throws Exception{try(InputStream in=input;OutputStream out=new FileOutputStream(target)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    static void run(SmokeTests test)throws Exception{
        Context context=test.getTargetContext();
        JSONObject reference=new JSONObject(ModelStore.text(test.getContext().getAssets().open("library-reference.json")));
        File task=new File(context.getFilesDir(),"library-test-task");task.mkdirs();File ply=new File(task,"generated_1200.ply");copy(test.getContext().getAssets().open("generated-chair.ply"),ply);
        ModelStore.write(new File(task,"result.json"),new JSONObject().put("phase","completed").put("model",ply.getName()));
        ModelStore.write(new File(task,"capture-source.json"),new JSONObject().put("capture_id",reference.getString("capture_id")));
        String id=ModelStore.importGenerated(context,ply,.8f);JSONObject meta=ModelStore.metadata(context,id);
        test.check(id.equals(reference.getString("source_sha256")),"Library identity matches the phone-generated source file");
        test.check(meta.getInt("gaussians")==reference.getInt("retained_gaussians"),"Phone silhouette crop matches independent Python count");
        test.check(meta.getString("retained_source_indices_sha256").equals(reference.getString("retained_source_indices_sha256")),"Every retained Gaussian matches independent multi-view projection");
        PackedModel actual,expected;try(InputStream in=ModelStore.openPacked(context,id)){actual=PackedModel.read(in);}try(InputStream in=test.getContext().getAssets().open("expected-model.bin")){expected=PackedModel.read(in);}
        boolean agrees=actual.count==expected.count;for(int i=0;i<9;i++)agrees&=Math.abs(actual.inverse[i]-expected.inverse[i])<1e-6;
        for(int i=0;i<actual.rows.length;i++)agrees&=Math.abs(actual.rows[i]-expected.rows[i])<1e-6+Math.abs(expected.rows[i])*3e-6;
        test.check(agrees,"All positions, covariances, SH colours and viewing rotation match Python reference");
        test.check(ModelStore.importGenerated(context,ply,.8f).equals(id),"Re-importing the same result reuses its immutable model");
        MainActivity imported=launch(test,id);test.check(imported.renderer.fatal==null&&imported.renderer.count==actual.count,"Stored model renders directly without repackaging");
        test.check(!imported.dimensionsConfirmed&&Math.abs(imported.requestedHeight-.8f)<.0001,"New model uses height hint and still requires confirmation");
        test.runOnMainSync(()->imported.applyDimensions(.7f,.5f,.9f,true));
        MainActivity builtin=launch(test,ModelStore.BUILTIN);test.check(builtin.renderer.count==9880&&Math.abs(builtin.requestedHeight-1)<.0001,"Switching back preserves original model and its own dimensions");
        MainActivity reopened=launch(test,id);test.check(Math.abs(reopened.requestedHeight-.9f)<.0001&&reopened.dimensionsConfirmed,"Saved model dimensions survive reopening");test.capture("test-stored-model.png");
        File packed=new File(new File(context.getFilesDir(),"models/"+id),"model.bin");byte[] original;
        try(InputStream in=new FileInputStream(packed)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)b.write(block,0,n);original=b.toByteArray();}
        try(OutputStream out=new FileOutputStream(packed)){out.write(new byte[]{1,2,3});}
        boolean rejected=false;try{ModelStore.metadata(context,id);}catch(Exception error){rejected=true;}
        test.check(rejected,"Corrupt stored model is rejected before renderer loading");
        MainActivity fallback=launch(test,id);test.check(ModelStore.BUILTIN.equals(fallback.activeModelId)&&fallback.renderer.fatal==null,"Corrupt selection falls back to the original model");
        try(OutputStream out=new FileOutputStream(packed)){out.write(original);}
        File broken=new File(task,"truncated.ply");try(OutputStream out=new FileOutputStream(broken)){out.write("ply\n".getBytes("US-ASCII"));}
        ModelStore.write(new File(task,"result.json"),new JSONObject().put("phase","completed").put("model",broken.getName()));int before=ModelStore.list(context).size();rejected=false;
        try{ModelStore.importGenerated(context,broken,1);}catch(Exception error){rejected=true;}
        test.check(rejected&&ModelStore.list(context).size()==before,"Failed import does not publish a partial model");
        test.check(ModelStore.BUILTIN.equals(ModelStore.active(context)),"Failed import preserves the active usable model");
        launch(test,ModelStore.BUILTIN);
    }
}
