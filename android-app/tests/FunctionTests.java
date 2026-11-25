package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import com.google.ar.core.Pose;
import com.google.zxing.*;
import com.google.zxing.common.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Emulator functional regression; uses isolated layout files and synthetic poses. */
public class FunctionTests extends Instrumentation {
    StringBuilder checks=new StringBuilder();MainActivity a;File temp;
    void check(boolean value,String text){if(!value)throw new AssertionError(text);checks.append("PASS ").append(text).append('\n');}
    void captureUi(String name)throws IOException{
        android.graphics.Bitmap image=getUiAutomation().takeScreenshot();
        if(image==null)return;
        try(FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        finally{image.recycle();}
    }
    interface Work {void run()throws Exception;}
    void gl(Work work)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};a.surface.queueEvent(()->{try{work.run();}catch(Throwable e){error[0]=e;}finally{done.countDown();}});if(!done.await(10,TimeUnit.SECONDS))throw new IOException("GL timeout");if(error[0]!=null)throw new RuntimeException(error[0]);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("model_id",ModelStore.BUILTIN);
        a=(MainActivity)startActivitySync(launch);Thread.sleep(1200);
        String payload=new JSONObject().put("type","furniture-pair").put("version",1).put("url","http://192.168.1.23:8268").put("code","01234567").toString();
        BitMatrix qr=new MultiFormatWriter().encode(payload,BarcodeFormat.QR_CODE,512,512);int[] pixels=new int[512*512];for(int y=0;y<512;y++)for(int x=0;x<512;x++)pixels[y*512+x]=qr.get(x,y)?0xff000000:0xffffffff;
        String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(512,512,pixels)))).getText();String[] pair=PairingCode.parse(decoded);
        check(pair[0].equals("http://192.168.1.23:8268")&&pair[1].equals("01234567"),"QR encode/decode roundtrip preserves address and leading-zero code");
        for(String url:new String[]{"https://192.168.1.2", "http://8.8.8.8", "http://user:pass@192.168.1.2", "http://192.168.1.2/path", "http://192.168.1.2?x=1"}){boolean rejected=false;try{ComputerActivity.normalizeUrl(url);}catch(Exception e){rejected=true;}check(rejected,"Reject unsupported QR/network destination "+url);}
        boolean bad=false;try{PairingCode.parse("{\"type\":\"other\"}");}catch(Exception e){bad=true;}check(bad,"Unrelated QR rejected");
        temp=new File(a.getCacheDir(),"layout-functional-"+UUID.randomUUID());temp.mkdirs();RoomLayoutStore store=new RoomLayoutStore(temp);
        JSONArray[] snapshot={null};gl(()->{RoomScene r=a.renderer.room;r.clear();RoomAsset asset=new RoomAsset(a,ModelStore.BUILTIN);RoomScene.Furniture first=new RoomScene.Furniture(asset),second=new RoomScene.Furniture(asset);
            Pose origin=Pose.makeTranslation(3,0,5).compose(Pose.makeRotation(0,(float)Math.sin(.35),0,(float)Math.cos(.35)));
            first.testPose=origin;first.width=.6f;first.height=.8f;first.depth=.7f;first.locked=true;first.yaw=.25f;
            second.testPose=origin.compose(Pose.makeTranslation(1.2f,0,-.4f));second.width=.9f;second.depth=.85f;second.height=1.2f;second.contact=.012f;second.yaw=-.5f;second.sizeMode="axes";second.locked=false;
            r.items.add(first);r.items.add(second);r.itemCount=2;r.selected=null;snapshot[0]=r.snapshot();});
        JSONObject saved=store.save("两件家具",snapshot[0]);String id=saved.getString("id");RoomLayoutStore reopened=new RoomLayoutStore(temp);JSONObject loaded=reopened.read(id);JSONArray entries=loaded.getJSONArray("items");
        check(entries.length()==2,"Two instances of one model survive durable save and reopen");
        JSONObject second=entries.getJSONObject(1);check(Math.abs(second.getJSONArray("translation").getDouble(0)-1.2)<1e-5&&Math.abs(second.getJSONArray("translation").getDouble(2)+.4)<1e-5,"Relative offsets preserved under a rotated, translated reference");
        check(Math.abs(second.getDouble("height")-1.2)<1e-5&&Math.abs(second.getDouble("width")-.9)<1e-5&&Math.abs(second.getDouble("contact")-.012)<1e-5&&!second.getBoolean("locked")&&second.getString("size_mode").equals("axes"),"Independent dimensions, contact and lock state survive save");
        Pose newOrigin=Pose.makeTranslation(-2,0,1).compose(Pose.makeRotation(0,1,0,0));Pose restored=newOrigin.compose(new Pose(RoomScene.vector(second.getJSONArray("translation")),RoomScene.vector(second.getJSONArray("rotation"))));float[] t=restored.getTranslation();check(Math.abs(t[0]+3.2)<1e-5&&Math.abs(t[2]-1.4)<1e-5,"Restore math maps the whole layout to a new reference");
        Pose camera=Pose.makeTranslation(-2,1.5f,3);
        Pose closer=RoomScene.cameraDistanceShift(newOrigin,camera,.20f);
        Pose movedFirst=closer.compose(newOrigin),movedSecond=closer.compose(restored);
        check(Math.abs(movedFirst.tz()-1.2f)<1e-5&&movedFirst.ty()==newOrigin.ty(),"Moving closer translates 20 cm on the floor despite camera height");
        Pose movedRelative=movedFirst.inverse().compose(movedSecond);
        check(Math.abs(movedRelative.tx()-1.2f)<1e-5&&Math.abs(movedRelative.tz()+.4f)<1e-5,"Moving the whole group preserves layout spacing and axes");
        Pose farther=RoomScene.cameraDistanceShift(newOrigin,camera,-.20f);
        check(Math.abs(farther.compose(newOrigin).tz()-.8f)<1e-5,"Moving farther follows the same ground direction away from camera");
        Pose limit=RoomScene.cameraDistanceShift(newOrigin,Pose.makeTranslation(-2,1.5f,1.5f),.20f);
        check(Math.abs(limit.tz()-.05f)<1e-5,"Closer adjustment stops at the minimum first-item distance");
        reopened.rename(id,"客厅");check(reopened.read(id).getString("name").equals("客厅"),"Rename survives reread");
        JSONArray fake=new JSONArray(snapshot[0].toString());String model=String.join("",Collections.nCopies(64,"a"));fake.getJSONObject(0).put("model_id",model);JSONObject protecting=store.save("引用保护",fake);
        bad=false;try{new LibraryStore(temp).removeModel(model);}catch(IOException e){bad=true;}check(bad&&!new LibraryStore(temp).deleted("models",model),"Referenced model cannot be tombstoned or deleted");
        store.remove(protecting.getString("id"));check(!store.references(model),"Deleting a layout releases model reference");
        bad=false;try{store.read("../outside");}catch(Exception e){bad=true;}check(bad,"Layout path traversal rejected");
        JSONObject malformed=new JSONObject(loaded.toString());malformed.getJSONArray("items").getJSONObject(0).put("rotation",new JSONArray("[0,0,0,0]"));bad=false;try{RoomLayoutStore.validate(malformed);}catch(Exception e){bad=true;}check(bad,"Invalid quaternion rejected before restore");
        store.remove(id);check(store.list().isEmpty(),"Deleting layout does not leave a visible stale record");
        gl(()->a.renderer.room.clear());check(a.ui.home.getVisibility()==android.view.View.VISIBLE,"Home remains available after layout operations");
        captureUi("ui-v015-home.png");
        int previewHeight=a.surface.getHeight();
        runOnMainSync(()->a.ui.setRoom(true));waitForIdleSync();Thread.sleep(200);
        int cameraHeight=a.surface.getHeight();
        check(cameraHeight>previewHeight&&a.surface.getWidth()==a.ui.viewport.getWidth(),"Room camera fills the available screen area");
        check(a.ui.toolsCollapsed&&!a.ui.details.isShown()&&a.ui.roomBack.isShown(),"Room starts with tools tucked away and exit visible");
        check(a.ui.saveLayout.isShown()&&!a.ui.saveLayout.isEnabled()&&a.ui.moveGroup.isShown()&&!a.ui.moveGroup.isEnabled(),"Layout actions stay visible and wait for a placed item");
        captureUi("ui-v015-room.png");
        runOnMainSync(()->a.ui.toolsToggle.performClick());waitForIdleSync();Thread.sleep(200);
        check(a.ui.details.isShown()&&a.ui.toolScroll.isShown()&&a.surface.getHeight()==cameraHeight,"Opening tools leaves the camera geometry unchanged");
        captureUi("ui-v015-tools.png");
        runOnMainSync(()->a.ui.toolTabs[1].performClick());waitForIdleSync();
        check(a.ui.depth.isShown()&&!a.ui.calibrate.isShown()&&a.surface.getHeight()==cameraHeight,"Display tab groups visual controls without resizing the camera");
        runOnMainSync(()->a.ui.toolTabs[2].performClick());waitForIdleSync();
        check(a.ui.reset.isShown()&&!a.ui.depth.isShown(),"Layout tab exposes rearrangement controls together");
        runOnMainSync(()->a.ui.toolsToggle.performClick());waitForIdleSync();Thread.sleep(200);
        check(!a.ui.details.isShown()&&a.surface.getHeight()==cameraHeight&&a.ui.saveLayout.isShown(),"Closing tools keeps the same camera view and quick actions");
        runOnMainSync(()->a.ui.setRoom(false));waitForIdleSync();
        check(a.ui.home.isShown()&&a.arButton.isShown()&&!a.ui.roomBottom.isShown()&&a.ui.ruler.isShown(),"Returning home restores the model preview controls");
        runOnMainSync(()->a.showModels());waitForIdleSync();Thread.sleep(250);
        android.view.accessibility.AccessibilityNodeInfo library=getUiAutomation().getRootInActiveWindow();
        check(library!=null&&!library.findAccessibilityNodeInfosByText("素材库").isEmpty()&&!library.findAccessibilityNodeInfosByText("房间布局").isEmpty(),"The library groups saved layouts with furniture and scenes");
        captureUi("ui-v015-library.png");sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
        runOnMainSync(()->FurnitureLibrary.chooseGeneration(a,"scan-1000-12345678"));waitForIdleSync();Thread.sleep(250);
        android.view.accessibility.AccessibilityNodeInfo generation=getUiAutomation().getRootInActiveWindow();
        check(generation!=null&&!generation.findAccessibilityNodeInfosByText("电脑生成").isEmpty()&&!generation.findAccessibilityNodeInfosByText("手机生成").isEmpty(),"Generation still offers the laptop and on-device choices");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
        runOnMainSync(()->RoomLayouts.show(a));waitForIdleSync();sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        check(a.renderer.fatal==null,"Gaussian renderer remains valid");
        result.putString("stream",checks+"Scope: synthetic poses; real camera QR scanning and physical AR placement require phone acceptance.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",checks+"FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}finally{if(temp!=null)try{new LibraryStore(a.getCacheDir()).erase(temp);}catch(Exception ignored){}}}
}
