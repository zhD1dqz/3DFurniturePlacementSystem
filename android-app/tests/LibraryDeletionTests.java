package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.util.*;
import org.json.*;

/** Destructive storage cases run only in a unique test cache directory. */
public class LibraryDeletionTests extends SmokeTests {
    void write(File file,String content)throws Exception{file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){out.write(content.getBytes("UTF-8"));}}
    Set<String> ids(Context c){Set<String> out=new HashSet<>();for(JSONObject m:ModelStore.list(c))out.add(m.optString("id"));return out;}
    boolean click(String text)throws Exception{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null||!"com.furniture.chairdemo".contentEquals(root.getPackageName()))return false;for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text))if(n.isClickable()&&text.contentEquals(n.getText())&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;return false;}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();Set<String> original=ids(c);String active=ModelStore.active(c);
        File root=new File(c.getCacheDir(),"library-delete-test-"+UUID.randomUUID());root.mkdirs();LibraryStore store=new LibraryStore(root);
        String parent=String.format(Locale.US,"%064d",1),child=String.format(Locale.US,"%064d",2),sibling=String.format(Locale.US,"%064d",3);
        File task=new File(root,"probe-1000-1200"),ply=new File(task,"generated_2400.ply");write(ply,"private test scene");String scene=PlyModelImporter.sha(ply);
        File packed=new File(root,"scene-models/"+scene+"/scene.bin"),capture=new File(root,"captures/scan-1000-12345678/capture.json");write(packed,"source needed by saved furniture");write(capture,"original scan remains");
        JSONObject completion=new JSONObject().put("phase","completed").put("model",ply.getName()).put("capture_id","scan-1000-12345678");ModelStore.write(new File(task,"integration-result.json"),completion);
        write(new File(root,"models/"+parent+"/model.json"),new JSONObject().put("source_scene_id",scene).toString());
        write(new File(root,"models/"+child+"/model.json"),new JSONObject().put("calibration_source_id",parent).put("source_scene_id",scene).toString());write(new File(root,"models/"+child+"/model.bin"),"independent calibrated data");
        write(new File(root,"models/"+sibling+"/model.json"),new JSONObject().put("source_scene_id",scene).toString());
        store.removeModel(parent);check(store.deleted("models",parent),"Deleted model is hidden durably");check(new File(root,"models/"+parent).isDirectory(),"Deleting a source preserves a surviving calibrated model's dependency");
        store.removeScene(task.getName());check(!task.exists()&&store.deleted("tasks",task.getName()),"Selected scene task and its generated files are removed");check(packed.exists(),"Saved furniture retains the source required for recropping");check(capture.exists(),"Deleting a scene preserves original capture data");
        File shared=new File(root,"probe-2000-1200");write(new File(shared,ply.getName()),"private test scene");ModelStore.write(new File(shared,"integration-result.json"),completion);
        store.removeModel(child);check(!new File(root,"models/"+child).exists()&&!new File(root,"models/"+parent).exists(),"Unreferenced deleted parent and child files are reclaimed");check(new File(root,"models/"+sibling).exists(),"Sibling furniture is preserved");
        store.removeModel(sibling);check(packed.exists(),"A second saved task sharing the scene keeps its packed data");store.removeScene(shared.getName());check(!packed.getParentFile().exists(),"Last deletion reclaims unreferenced packed scene data");check(capture.exists(),"All library deletions leave the raw capture intact");
        boolean rejected=false;try{store.removeModel("builtin");}catch(IOException expected){rejected=true;}check(rejected,"Built-in sample cannot be deleted");rejected=false;try{store.removeScene("../captures");}catch(IOException expected){rejected=true;}check(rejected,"Scene path traversal is rejected");
        File outside=new File(c.getCacheDir(),"delete-boundary-"+UUID.randomUUID());write(outside,"must survive");rejected=false;try{store.erase(outside);}catch(IOException expected){rejected=true;}check(rejected&&outside.exists(),"Cleanup refuses a path outside its own library root");
        final File isolated=new File(c.getCacheDir(),"delete-context-"+UUID.randomUUID());isolated.mkdirs();final String prefs="delete-test-"+UUID.randomUUID();
        Context wrapped=new ContextWrapper(c){@Override public File getFilesDir(){return isolated;}@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return c.getSharedPreferences(prefs+name,mode);}};
        String id=String.format(Locale.US,"%064d",7);File modelRoot=new File(isolated,"models/"+id);modelRoot.mkdirs();File data=new File(modelRoot,"model.bin");
        try(InputStream in=c.getAssets().open("chair.bin");OutputStream out=new FileOutputStream(data)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
        PackedModel fixture;try(InputStream in=new FileInputStream(data)){fixture=PackedModel.read(in);}
        ModelStore.write(new File(modelRoot,"model.json"),new JSONObject().put("id",id).put("schema_version",1).put("gaussians",fixture.count).put("packed_sha256",PlyModelImporter.sha(data)));
        ModelStore.write(new File(modelRoot,"dimensions.json"),new JSONObject().put("width_m",1).put("depth_m",1).put("height_m",1));ModelStore.activate(wrapped,id);check(id.equals(ModelStore.active(wrapped))&&ModelStore.list(wrapped).size()==1,"Real model-store integration accepts isolated valid fixture");
        String rawId="scan-1000-12345678";File raw=new File(isolated,"captures/"+rawId+"/capture.json");write(raw,new JSONObject().put("id",rawId).toString());
        File namedScene=new File(isolated,"probe-1720-1200");write(new File(namedScene,"integration-result.json"),completion.toString());
        String rawHash=PlyModelImporter.sha(raw);LibraryNames.renameCapture(wrapped,rawId,"　客厅　");
        check("客厅".equals(LibraryNames.capture(wrapped,rawId))&&rawHash.equals(PlyModelImporter.sha(raw)),"Renaming a capture trims its title and preserves its upload manifest");
        check("客厅".equals(LibraryNames.scene(wrapped,namedScene.getName(),rawId,"computer")),"A reconstruction picks up the capture title by default");
        LibraryNames.renameScene(wrapped,namedScene,"主卧");LibraryNames.renameCapture(wrapped,rawId,"餐厅");
        check("主卧".equals(LibraryNames.scene(wrapped,namedScene.getName(),rawId,"computer"))&&"餐厅".equals(LibraryNames.capture(wrapped,rawId)),"Scene and camera record titles can be edited independently");
        String geometryHash=PlyModelImporter.sha(data);LibraryNames.renameFurniture(wrapped,ModelStore.metadata(wrapped,id),"餐桌");
        check("餐桌".equals(ModelStore.metadata(wrapped,id).getString("name"))&&id.equals(ModelStore.active(wrapped))&&geometryHash.equals(PlyModelImporter.sha(data)),"Renaming furniture preserves its ID, selection and model bytes");
        rejected=false;try{LibraryNames.renameCapture(wrapped,rawId,"　");}catch(IOException expected){rejected=true;}
        check(rejected&&"餐厅".equals(LibraryNames.capture(wrapped,rawId)),"Blank titles cannot replace an existing name");
        LibraryStore.deleteModel(wrapped,id);check(ModelStore.BUILTIN.equals(ModelStore.active(wrapped))&&ModelStore.list(wrapped).isEmpty()&&!modelRoot.exists(),"Deleting active saved model falls back to sample and removes its files");
        activity=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();runOnMainSync(()->activity.showModels());Thread.sleep(600);
        AccessibilityNodeInfo window=null;long deadline=SystemClock.uptimeMillis()+8000;while(window==null&&SystemClock.uptimeMillis()<deadline){window=getUiAutomation().getRootInActiveWindow();if(window==null)Thread.sleep(100);}check(window!=null,"Library window is available to accessibility inspection");
        List<AccessibilityNodeInfo> menus=window.findAccessibilityNodeInfosByText("更多操作");check(!menus.isEmpty(),"Saved models or scenes expose item actions");
        check(menus.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Item menu opens");Thread.sleep(350);
        check(click("重命名"),"Item menu offers rename");Thread.sleep(350);check(click("取消"),"Renaming can be cancelled");Thread.sleep(250);
        window=getUiAutomation().getRootInActiveWindow();menus=window.findAccessibilityNodeInfosByText("更多操作");menus.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(350);
        check(click("删除"),"Delete opens a confirmation dialog");Thread.sleep(350);check(click("取消"),"Confirmation can be cancelled");Thread.sleep(250);check(original.equals(ids(c))&&active.equals(ModelStore.active(c)),"Cancelling leaves all real saved models and active selection unchanged");
        CaptureData.atomic(new File(c.getFilesDir(),"library-delete-test.json"),new JSONObject().put("status","passed").put("scope","Destructive cases in unique cache; actual library UI confirmation cancelled").put("checks",results.toString()));result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",results+"FAIL "+error);finish(Activity.RESULT_CANCELED,result);}}
}
