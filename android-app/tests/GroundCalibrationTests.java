package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import java.io.*;
import org.json.*;

/** The 80 cm value is the user's measurement of this specific captured chair. */
public class GroundCalibrationTests extends SmokeTests {
    static final String SOURCE="a536c407a23d1d9c246f0b5c6bf70b4b24b0ed6f5cb8ccafc50ae9bc9b19f7ac";
    MainActivity open(String id)throws Exception{MainActivity a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("model_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activity=a;waitForIdleSync();Thread.sleep(600);gl(()->{});nextFrames();return a;}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();String oldId=ModelStore.active(c);MainActivity old=open(oldId);float oldHeight=old.requestedHeight;
        File original=new File(GroundCalibration.modelFolder(c,SOURCE),"model.bin");String checksum=PlyModelImporter.sha(original);PackedModel source=GroundCalibration.model(c,SOURCE);float[] range=GroundCalibration.suggest(source);
        check(range[1]>range[0]+.5f&&range[0]<.05f,"Suggested contact and top references span this chair");
        boolean rejected=false;try{GroundCalibration.save(c,SOURCE,range[0],Float.NaN);}catch(IOException e){rejected=true;}check(rejected,"Invalid physical height is rejected");rejected=false;try{GroundCalibration.save(c,SOURCE,range[0]+1,.8f);}catch(IOException e){rejected=true;}check(rejected,"Implausible ground adjustment is rejected");
        String id=GroundCalibration.save(c,SOURCE,range[0],.8f);JSONObject meta=ModelStore.metadata(c,id),spec=meta.getJSONObject("calibration");PackedModel packed=GroundCalibration.model(c,id);float scale=(float)spec.getDouble("uniform_scale");
        check(id.equals(GroundCalibration.save(c,id,range[0],.8f)),"Recalibration reuses original crop without accumulating transforms");
        check("calibrated_furniture".equals(meta.getString("kind"))&&meta.getBoolean("ar_ready")&&SOURCE.equals(meta.getString("calibration_source_id")),"Metric furniture retains its immutable crop provenance");
        float err=0;for(int k=0;k<source.rows.length;k+=37){for(int j=0;j<37;j++){float expected=source.rows[k+j];if(j==0||j==2)expected*=scale;else if(j==1)expected=(expected-range[0])*scale;else if(j>=3&&j<=8)expected*=scale*scale;err=Math.max(err,Math.abs(expected-packed.rows[k+j]));}}
        check(packed.count==source.count&&err<1e-6,"All Gaussian positions and covariances scale together; colors and opacity remain intact");
        check(Math.abs((range[1]-range[0])*scale-.8f)<1e-6,"Contact-to-top reference is exactly the measured 80 cm");
        check(checksum.equals(PlyModelImporter.sha(original))&&!ModelStore.metadata(c,SOURCE).getBoolean("ar_ready"),"Calibration preserves the accepted crop and its original status");
        MainActivity a=open(id);check(a.renderer.fatal==null&&a.renderer.count==source.count,"Calibrated chair renders with the real phone GPU");check(a.dimensionsConfirmed&&Math.abs(a.requestedHeight-.8f)<1e-6&&a.arButton.getVisibility()==View.VISIBLE,"Verified height initializes dimensions and enables AR entry");
        runOnMainSync(()->{a.ui.setRoom(true);a.ui.setToolsCollapsed(false);a.groundPanel.open();});gl(()->{});nextFrames();check(a.showGroundGuide&&a.groundPanel.panel.isShown(),"Calibration shows an editable contact plane on the room setup preview");capture("ground-calibration-panel.png");runOnMainSync(()->{a.groundPanel.close();a.ui.setRoom(false);});gl(()->{});check(!a.showGroundGuide,"Cancel hides the contact guide without altering the model");
        runOnMainSync(()->{a.applyContactOffset(.02f);a.applyDimensions(a.requestedDepth,a.requestedWidth,.88f,true);});nextFrames();gl(()->{});
        check(Math.abs(a.renderer.model[13]-.02f)<1e-6&&Math.abs(a.renderer.model[5]-1.1f)<1e-5,"Contact offset stays two physical centimetres when furniture is rescaled");
        gl(()->a.renderer.placementLocked=true);rejected=false;try{a.applyContactOffset(.01f);}catch(IllegalStateException e){rejected=true;}check(rejected,"Locked furniture rejects contact edits");gl(()->a.renderer.placementLocked=false);
        runOnMainSync(()->{a.applyContactOffset(0);a.applyDimensions(a.requestedDepth,a.requestedWidth,.8f,true);a.sizePreferences().edit().putFloat("contactOffsetM",0).apply();a.loadDimensions();});check(a.dimensionsConfirmed&&Math.abs(a.requestedHeight-.8f)<1e-6,"User dimensions persist after calibrated metadata has been applied");
        final int[] visible=new int[2];gl(()->{visible[0]=syntheticDepthPixels(300,300);visible[1]=syntheticDepthPixels(7000,7000);});check(visible[0]==0&&visible[1]>500,"Existing depth shader occludes this new model without bypassing it (near/far pixels: "+visible[0]+"/"+visible[1]+")");nextFrames();
        runOnMainSync(()->a.showSizeGuide=true);capture("ground-calibrated-preview.png");runOnMainSync(()->a.showSizeGuide=false);
        runOnMainSync(()->a.startAR());Thread.sleep(1500);check(a.session!=null&&a.depthSupported,"Real phone starts calibrated-furniture AR with depth support");runOnMainSync(()->a.leaveAR());nextFrames();
        check(checksum.equals(PlyModelImporter.sha(original)),"AR preview does not modify original geometry");
        MainActivity restored=open(oldId);check(Math.abs(restored.requestedHeight-oldHeight)<1e-6,"Previously selected furniture keeps its dimensions");
        ModelStore.write(new File(c.getFilesDir(),"ground-test-report.json"),new JSONObject().put("model_id",id).put("source_crop_id",SOURCE).put("model",meta).put("ground",range[0]).put("top_reference",range[1]).put("scale",scale).put("height_m",.8).put("checks",results.toString()).put("physical_placement_review","pending_user"));
        result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream",results+"FAIL "+e);finish(Activity.RESULT_CANCELED,result);}}
}
