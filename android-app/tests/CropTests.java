package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;
import org.json.*;

public class CropTests extends SmokeTests {
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();String oldActive=ModelStore.active(context);activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();float oldHeight=activity.requestedHeight;
        String sceneId=SceneStore.prepare(context,SceneStore.latestTask(context));File original=new File(SceneStore.folder(context,sceneId),"scene.bin");String checksum=PlyModelImporter.sha(original);
        SceneViewerActivity scene=(SceneViewerActivity)startActivitySync(new Intent(context,SceneViewerActivity.class).putExtra("scene_id",sceneId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=scene;waitForIdleSync();Thread.sleep(700);gl(()->{});
        int full=scene.renderer.count;runOnMainSync(()->{scene.cropEditor.open();scene.cropEditor.preview.setChecked(true);});gl(()->{});nextFrames();CropBox initial=scene.cropEditor.box;
        check(scene.renderer.count>=100&&scene.renderer.count<full,"Suggested range removes background and retains a usable subset");
        float[] selected=CropBox.select(scene.sceneRenderer.sourceRows,initial);check(selected.length/37==scene.renderer.count,"Live crop preview matches source-data selection");capture("crop-preview.png");
        CropBox moved=new CropBox(initial.x+.1f,initial.y,initial.z,initial.width,initial.height,initial.depth,.15f);
        runOnMainSync(()->{scene.cropEditor.history.push(initial);scene.cropEditor.box=moved;scene.cropEditor.apply();((ViewGroup)scene.cropEditor.panel.getChildAt(3)).getChildAt(0).performClick();});gl(()->{});check(scene.cropEditor.box==initial&&scene.renderer.count==selected.length/37,"Undo restores the prior range and preview");
        runOnMainSync(()->scene.cropEditor.cancel());gl(()->{});check(scene.renderer.count==full&&scene.sceneRenderer.cropBox==null,"Cancel restores the full scene");
        check(PlyModelImporter.sha(original).equals(checksum),"Preview, undo and cancel never rewrite original model");
        CropBox rotated=new CropBox(initial.x,initial.y,initial.z,initial.width+.05f,initial.height,initial.depth+.05f,.15f);String id=CropStore.save(context,sceneId,rotated,"餐厅的椅子");JSONObject meta=ModelStore.metadata(context,id);
        check("餐厅的椅子".equals(meta.getString("name")),"Saving a crop accepts a custom furniture title");
        check(meta.getInt("gaussians")==CropBox.select(scene.sceneRenderer.sourceRows,rotated).length/37,"Saved crop uses precisely the selected source Gaussians");
        check(id.equals(CropStore.save(context,sceneId,rotated)),"Saving the same range reuses the same immutable furniture");
        float[] range=GroundCalibration.suggest(GroundCalibration.model(context,id));String calibrated=GroundCalibration.save(context,id,range[0],.8f);
        check("餐厅的椅子".equals(ModelStore.metadata(context,calibrated).getString("name")),"Calibrated copies keep the crop title");
        check(!meta.getBoolean("ar_ready")&&"cropped_furniture_pending".equals(meta.getString("kind")),"Extracted furniture is clearly pending size and AR validation");
        boolean rejected=false;try{CropStore.save(context,sceneId,new CropBox(100,100,100,.1f,.1f,.1f,0));}catch(IOException expected){rejected=true;}check(rejected,"Empty crop is rejected before publication");
        check(oldActive.equals(ModelStore.active(context))&&PlyModelImporter.sha(original).equals(checksum),"Saving a crop preserves selected furniture and full scene");
        MainActivity extracted=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).putExtra("model_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activity=extracted;waitForIdleSync();Thread.sleep(700);gl(()->{});nextFrames();
        check(extracted.renderer.fatal==null&&extracted.renderer.count==meta.getInt("gaussians"),"Saved independent furniture renders with covariance and SH colors");
        check(extracted.pendingCrop()&&!meta.getBoolean("ar_ready")&&extracted.session==null,"Crop still awaits calibration before starting an AR camera session");capture("crop-saved.png");
        SceneViewerActivity edit=(SceneViewerActivity)startActivitySync(new Intent(context,SceneViewerActivity.class).putExtra("scene_id",sceneId).putExtra("edit_crop_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=edit;waitForIdleSync();Thread.sleep(700);gl(()->{});
        check(edit.cropEditor.draft&&edit.cropEditor.preview.isChecked()&&edit.renderer.count==meta.getInt("gaussians"),"Saved crop reopens in editable preview against the original scene");
        check(Math.abs(edit.cropEditor.box.yaw-.15f)<1e-5,"Saved range orientation survives reopening");
        check("餐厅的椅子".equals(edit.editedFurnitureName),"Re-cropping keeps the custom name in the save dialog");
        JSONObject report=new JSONObject().put("crop_id",id).put("scene_id",sceneId).put("model",meta).put("checks",results.toString());ModelStore.write(new File(context.getFilesDir(),"crop-test-report.json"),report);
        MainActivity old=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).putExtra("model_id",oldActive).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activity=old;waitForIdleSync();Thread.sleep(500);gl(()->{});check(Math.abs(old.requestedHeight-oldHeight)<1e-5&&old.renderer.fatal==null,"Original furniture and measured dimensions still reopen unchanged");
        result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream",results+"FAIL "+e);finish(Activity.RESULT_CANCELED,result);}}
}
