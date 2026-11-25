package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.opengl.*;
import android.os.*;
import android.view.*;
import com.google.ar.core.Pose;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** Real-device UI/GPU checks. Test poses do not claim physical plane placement. */
public class RoomUiTests extends SmokeTests {
    static final String CAL="6bba7f6269e812ecb5c4510744e27365ebe33bad60a8a379ae653bc1b7dd281a";
    byte[] pixels(){MainActivity.ChairRenderer r=activity.renderer;ByteBuffer b=ByteBuffer.allocateDirect(r.width*r.height*4);GLES30.glReadPixels(0,0,r.width,r.height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,b);byte[] bytes=new byte[b.capacity()];b.get(bytes);return bytes;}
    void clear(){GLES30.glClearColor(.085f,.115f,.145f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);}
    int difference(byte[] x,byte[] y){int max=0;for(int i=0;i<x.length;i++)max=Math.max(max,Math.abs((x[i]&255)-(y[i]&255)));return max;}
    int nonbackground(byte[] x){int n=0;for(int i=0;i<x.length;i+=4)if(Math.abs((x[i]&255)-22)+Math.abs((x[i+1]&255)-29)+Math.abs((x[i+2]&255)-37)>25)n++;return n;}
    void save(byte[] p,String name)throws Exception{int w=activity.renderer.width,h=activity.renderer.height;int[] colours=new int[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int k=(y*w+x)*4;colours[(h-1-y)*w+x]=0xff000000|((p[k]&255)<<16)|((p[k+1]&255)<<8)|(p[k+2]&255);}Bitmap b=Bitmap.createBitmap(colours,w,h,Bitmap.Config.ARGB_8888);try(FileOutputStream f=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        String old=ModelStore.active(getTargetContext());activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("model_id",CAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();Thread.sleep(650);gl(()->{});nextFrames();final MainActivity a=activity;
        check("家居重建".equals(a.getApplicationInfo().loadLabel(a.getPackageManager()).toString()),"Launcher application name is 家居重建");check(a.renderer.fatal==null&&CAL.equals(a.activeModelId)&&a.renderer.count==2425,"Extended shader compiles and the selected calibrated model renders");
        check(!a.roomMode&&a.ui.home.getVisibility()==View.VISIBLE&&a.ui.toolScroll.getVisibility()==View.GONE&&a.status.getVisibility()==View.GONE,"Home hides gesture hints, calibration, lock and depth tools");check(a.ui.ruler.isShown()&&a.arButton.isShown(),"Home keeps size ruler and room entry");capture("ui-home.png");
        runOnMainSync(()->a.showModels());capture("ui-model-library.png");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
        runOnMainSync(()->a.startGeneration());capture("ui-generation-picker.png");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
        RoomAsset first=new RoomAsset(a,CAL),second=new RoomAsset(a,ModelStore.BUILTIN);RoomScene room=a.renderer.room;
        final byte[][] p=new byte[4][];final RoomScene.Furniture[] f=new RoomScene.Furniture[3];final int[] stats=new int[4];
        gl(()->{a.renderer.onDrawFrame(null);p[0]=pixels();f[0]=new RoomScene.Furniture(first);f[0].testPose=Pose.IDENTITY;f[0].locked=true;room.items.add(f[0]);room.itemCount=1;room.select(f[0]);clear();room.draw(a.renderer);p[1]=pixels();stats[0]=GLES30.glGetError();});
        if(difference(p[0],p[1])>1){save(p[0],"room-parity-normal.png");save(p[1],"room-parity-batch.png");}
        check(stats[0]==GLES30.GL_NO_ERROR&&difference(p[0],p[1])<=1,"Single furniture texture-transform path matches original Gaussian pixels; max difference="+difference(p[0],p[1])+", GL="+stats[0]+", height="+a.requestedHeight+", contact="+a.contactOffsetM);
        gl(()->{f[1]=new RoomScene.Furniture(second);f[1].testPose=Pose.makeTranslation(.75f,0,-.35f);f[1].locked=true;room.items.add(f[1]);room.itemCount=2;room.select(f[1]);clear();room.draw(a.renderer);p[2]=pixels();stats[1]=room.drawCount;stats[2]=GLES30.glGetError();});
        check(stats[1]==first.count+second.count&&stats[2]==GLES30.GL_NO_ERROR&&nonbackground(p[2])>500,"Two different complete Gaussian models render together on phone GPU");save(p[2],"room-two-furniture-gpu.png");
        gl(()->{Collections.reverse(room.items);clear();room.draw(a.renderer);p[3]=pixels();});check(difference(p[2],p[3])<=1,"Transparency ordering is invariant to furniture insertion order");
        float initialHeight=f[0].height,initialYaw=f[0].yaw;gl(()->{a.renderer.placementLocked=false;a.renderer.drag(50,0);a.requestedHeight=.9f;a.contactOffsetM=.01f;room.sync();});check(Math.abs(f[0].height-initialHeight)<1e-6&&f[0].yaw==initialYaw&&Math.abs(f[1].height-.9f)<1e-6&&f[1].yaw!=initialYaw,"Editing one selected furniture leaves other dimensions and rotation intact");
        gl(()->{f[2]=new RoomScene.Furniture(first);f[2].testPose=Pose.makeTranslation(-.7f,0,-.3f);room.items.add(f[2]);room.itemCount=3;room.select(f[2]);});check(f[2].asset.rows==f[0].asset.rows&&f[2]!=f[0]&&room.size()==3,"Duplicate furniture shares immutable data and has a separate placement");
        gl(()->room.removeSelected());check(room.size()==2&&room.items.contains(f[0])&&room.items.contains(f[1]),"Removing selected furniture preserves both other placements");
        gl(()->{a.renderer.onDrawFrame(null);float[] c=new float[4],vp=new float[16];Matrix.multiplyMM(vp,0,a.renderer.projection,0,a.renderer.view,0);Matrix.multiplyMV(c,0,vp,0,new float[]{0,initialHeight*.5f,0,1},0);float x=(c[0]/c[3]+1)*.5f*a.renderer.width,y=(1-c[1]/c[3])*.5f*a.renderer.height;stats[3]=room.pick(x,y,a.renderer)==f[0]?1:0;});check(stats[3]==1,"Screen tap ray picks an existing furniture at its visible location");
        final int[] visibility=new int[2];gl(()->{for(int i=0;i<2;i++){ByteBuffer d=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());d.putShort((short)(i==0?300:7000)).putShort((short)255).flip();GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,a.renderer.depthTex);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RG16UI,1,1,0,GLES30.GL_RG_INTEGER,GLES30.GL_UNSIGNED_SHORT,d);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);a.renderer.depthReady=true;a.renderer.depthUvTransform=new float[]{1,0,0,0,1,0,0,0,1};clear();room.draw(a.renderer);visibility[i]=nonbackground(pixels());}a.renderer.depthReady=false;room.clear();a.activeModelId=CAL;a.activeModel=first.metadata;a.nominalWidth=first.width;a.nominalDepth=first.depth;a.nominalHeight=first.height;a.requestedWidth=first.width;a.requestedDepth=first.depth;a.requestedHeight=first.height;a.contactOffsetM=0;});
        check(visibility[0]==0&&visibility[1]>500,"Real-depth shader occludes all furniture in the combined batch");
        runOnMainSync(()->{a.startAR();a.ui.setToolsCollapsed(false);});Thread.sleep(1600);gl(()->{});check(a.session!=null&&a.roomMode&&a.ui.home.getVisibility()==View.GONE&&a.ui.toolScroll.getVisibility()==View.VISIBLE,"Real AR session switches home to room tools");capture("ui-room.png");check(a.ui.calibrate.isShown()&&a.ui.contact.isShown(),"Furniture tab shows calibration and contact controls");runOnMainSync(()->a.ui.toolTabs[1].performClick());waitForIdleSync();check(a.ui.depth.isShown(),"Display tab shows the depth view");runOnMainSync(()->a.ui.toolTabs[2].performClick());waitForIdleSync();check(a.ui.reset.isShown(),"Layout tab shows reset");runOnMainSync(()->a.ui.toolTabs[0].performClick());waitForIdleSync();
        final int expandedHeight=a.surface.getHeight();final float heightBefore=a.requestedHeight;runOnMainSync(()->a.ui.toolsToggle.performClick());waitForIdleSync();Thread.sleep(300);check(!a.ui.details.isShown()&&a.ui.toolsToggle.isShown()&&a.surface.getHeight()==expandedHeight,"Collapsing the overlay preserves the camera viewport");check(a.ui.scenes.isShown()&&a.ui.roomBack.isShown()&&a.ui.saveLayout.isShown()&&a.session!=null&&Math.abs(a.requestedHeight-heightBefore)<1e-6,"Collapsing preserves camera session, dimensions and placement controls");capture("ui-room-collapsed.png");runOnMainSync(()->a.ui.toolsToggle.performClick());waitForIdleSync();check(a.ui.details.isShown()&&a.ui.contact.isShown(),"Expand arrow restores room adjustment tools");
        runOnMainSync(()->a.showRoomModels());capture("ui-room-picker.png");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
        runOnMainSync(()->a.addRoomModel(ModelStore.BUILTIN,false));long until=SystemClock.uptimeMillis()+8000;while(!ModelStore.BUILTIN.equals(a.activeModelId)&&SystemClock.uptimeMillis()<until)Thread.sleep(50);check(ModelStore.BUILTIN.equals(a.activeModelId)&&room.pending!=null,"Room picker can queue a different saved furniture for floor placement");
        runOnMainSync(()->a.leaveAR());Thread.sleep(400);gl(()->{});check(!a.roomMode&&a.session==null&&a.ui.home.isShown()&&!a.ui.toolScroll.isShown()&&room.size()==0,"Leaving room restores clean home and releases scene placements");
        ModelStore.write(new File(getTargetContext().getFilesDir(),"room-ui-test-report.json"),new JSONObject().put("checks",results.toString()).put("gpu_two_model_count",stats[1]).put("actual_multi_anchor_placement","pending_user").put("scope","Real phone GPU with synthetic poses plus real AR camera startup; user must check physical two-furniture placement"));
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("model_id",old).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream",results+"FAIL "+e);finish(Activity.RESULT_CANCELED,result);}}
}
