package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

/** Publish a clearly labelled desktop-pose/phone-training diagnostic copy only. */
public class PoseImportTests extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        Context c=getTargetContext();JSONObject spec=ModelStore.json(new File(c.getFilesDir(),"pose-import-context.json"));String taskName=spec.getString("task"),oldId=spec.getString("reference_model_id");if(!taskName.matches("probe-\\d+-1200"))throw new IOException("Invalid task");
        JSONObject old=ModelStore.metadata(c,oldId);File original=new File(GroundCalibration.modelFolder(c,oldId),"model.bin");String before=PlyModelImporter.sha(original);String priorActive=ModelStore.active(c);
        File task=new File(c.getFilesDir(),taskName),ply=new File(task,"generated_1200.ply");if(!PlyModelImporter.sha(ply).equals(spec.getString("expected_sha256")))throw new IOException("Diagnostic PLY hash mismatch");
        String scene=SceneStore.prepare(c,task);CropBox box=CropBox.fromJson(old.getJSONObject("crop"));String cropped=CropStore.save(c,scene,box);PackedModel model=GroundCalibration.model(c,cropped);float[] reference=GroundCalibration.suggest(model);String calibrated=GroundCalibration.save(c,cropped,reference[0],.8f);
        for(String id:new String[]{cropped,calibrated}){JSONObject m=ModelStore.metadata(c,id);m.put("name",id.equals(calibrated)?"椅子 · 定位校正对照（80cm）":"定位校正 · 裁剪备份");m.put("diagnostic_origin","Desktop COLMAP pose refinement; phone GPU Gaussian training");m.put("phone_generation_seconds",spec.getDouble("phone_seconds"));m.put("pose_refinement_on_phone",false);ModelStore.write(new File(GroundCalibration.modelFolder(c,id),"model.json"),m);ModelStore.metadata(c,id);}
        File archive=new File(c.getFilesDir(),"quality-pose-result-"+taskName);if(archive.exists()||!task.renameTo(archive))throw new IOException("Cannot archive diagnostic task");JSONObject sceneMeta=SceneStore.load(c,scene);sceneMeta.put("source_task",archive.getName()).put("diagnostic_origin","Desktop poses; phone trained");ModelStore.write(new File(SceneStore.folder(c,scene),"scene.json"),sceneMeta);
        if(!before.equals(PlyModelImporter.sha(original))||!priorActive.equals(ModelStore.active(c)))throw new IOException("Original furniture changed");
        JSONObject report=new JSONObject().put("model_id",calibrated).put("crop_id",cropped).put("scene_id",scene).put("source_ply_sha256",spec.getString("expected_sha256")).put("original_furniture_unchanged",true).put("prior_selection_preserved_during_import",true).put("gaussians",ModelStore.metadata(c,calibrated).getInt("gaussians")).put("height_m",.8).put("pose_refinement_on_phone",false).put("physical_view_review","pending_user");ModelStore.write(new File(c.getFilesDir(),"pose-import-report.json"),report);
        out.putString("stream","PASS Diagnostic PLY, original crop range and 80cm calibrated copy saved\nPASS Original furniture and normal latest-scan task preserved\n"+report);finish(Activity.RESULT_OK,out);
    }catch(Throwable e){out.putString("stream","FAIL "+e);finish(Activity.RESULT_CANCELED,out);}}
}
