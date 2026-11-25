package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import org.json.*;

/** Reproduce the confirmed-size pending crop, then follow the real AR-entry action. */
public class CropArEntryTests extends SmokeTests {
    Bundle options;
    @Override public void onCreate(Bundle args){options=args;super.onCreate(args);}
    @Override public void onStart(){Bundle out=new Bundle();try{
        Context c=getTargetContext();String source=options.getString("source_id");if(source==null||!source.matches("[a-f0-9]{64}"))throw new AssertionError("Explicit current crop required");
        File original=new File(GroundCalibration.modelFolder(c,source),"model.bin");String checksum=PlyModelImporter.sha(original);
        MainActivity a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).putExtra("model_id",source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activity=a;waitForIdleSync();Thread.sleep(600);gl(()->{});
        check(a.pendingCrop()&&a.dimensionsConfirmed,"Reproduced pending crop with dimensions already confirmed");float width=a.requestedWidth,depth=a.requestedDepth,height=a.requestedHeight;String mode=a.sizeMode;
        runOnMainSync(()->{a.dimensionsConfirmed=false;a.enterRoom();});Thread.sleep(250);check(!a.roomMode&&a.session==null&&a.groundPanel.panel.getVisibility()!=View.VISIBLE,"Unconfirmed dimensions use a visible dialog without entering a false room state");
        boolean cancelled=false;AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("取消"))if(node.isClickable()&&"取消".contentEquals(node.getText())){cancelled=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);break;}check(cancelled,"Size confirmation can be cancelled without altering saved dimensions");runOnMainSync(()->a.dimensionsConfirmed=true);
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);runOnMainSync(()->a.enterRoom());Activity opened=waitForMonitorWithTimeout(monitor,20000);removeMonitor(monitor);check(opened instanceof MainActivity,"Room-entry preparation opens the grounded furniture copy automatically");MainActivity ready=(MainActivity)opened;activity=ready;waitForIdleSync();
        long deadline=SystemClock.elapsedRealtime()+20000;while(ready.session==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);gl(()->{});
        check(ready.calibratedFurniture()&&!ready.pendingCrop()&&ready.activeModel.optBoolean("ar_ready"),"Prepared copy has genuine ground calibration and AR-ready metadata");
        check(source.equals(ready.activeModel.getString("calibration_source_id")),"New AR copy refers to this user's actual crop");
        PackedModel before=GroundCalibration.model(c,source),after=GroundCalibration.model(c,ready.activeModelId);check(before.count==after.count,"Calibration preserves every Gaussian in the selected furniture");
        boolean appearanceUnchanged=true;for(int k=0;k<before.rows.length;k+=37)for(int j=9;j<37;j++)if(Float.floatToIntBits(before.rows[k+j])!=Float.floatToIntBits(after.rows[k+j]))appearanceUnchanged=false;check(appearanceUnchanged,"Opacity and all color coefficients are unchanged by numerical repair");
        check(ready.activeModel.optInt("covariances_stabilized_after_scale")>0,"This model exercises the formerly failing thin-Gaussian scaling case");
        check(ready.dimensionsConfirmed&&Math.abs(ready.requestedWidth-width)<1e-6&&Math.abs(ready.requestedDepth-depth)<1e-6&&Math.abs(ready.requestedHeight-height)<1e-6&&mode.equals(ready.sizeMode),"All confirmed dimensions and sizing mode survive copy and Activity recreation");
        check(ready.session!=null&&ready.roomMode&&ready.depthSupported,"Actual Xiaomi ARCore session starts with scene depth enabled");
        check(ready.ui.home.getVisibility()==View.GONE&&ready.ui.roomTop.getVisibility()==View.VISIBLE,"Room interface appears after AR starts");
        check(ready.renderer.room.pending!=null&&ready.renderer.room.pending.asset.confirmed&&ready.activeModelId.equals(ready.renderer.room.pending.asset.id),"Correct furniture is queued and ready for a real ground tap");
        check(checksum.equals(PlyModelImporter.sha(original)),"Original crop remains unchanged");
        capture("crop-ar-entry-fixed.png");JSONObject report=new JSONObject().put("status","passed").put("source_id",source).put("prepared_model_id",ready.activeModelId).put("width_m",width).put("depth_m",depth).put("confirmed_height_m",height).put("checks",results.toString()).put("physical_plane_hit","Requires user to move phone and tap a tracked floor; not simulated by this test");CaptureData.atomic(new File(c.getFilesDir(),"crop-ar-entry-test.json"),report);out.putString("stream",results.toString()+report);finish(Activity.RESULT_OK,out);
    }catch(Throwable error){out.putString("stream",results+"FAIL "+error);finish(Activity.RESULT_CANCELED,out);}}
}
