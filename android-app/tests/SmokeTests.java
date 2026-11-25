package com.furniture.chairdemo;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.view.MotionEvent;
import java.io.*;
import java.util.concurrent.*;
import java.nio.*;
import android.opengl.GLES30;

/** Instrumentation only; this class is packaged in a separate test APK. */
public class SmokeTests extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    MainActivity activity;
    StringBuilder results=new StringBuilder();
    int leftPixels,rightPixels;
    int syntheticDepthPixels(int leftMm,int rightMm){
        MainActivity.ChairRenderer r=activity.renderer;
        ByteBuffer depth=ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());depth.putShort((short)leftMm).putShort((short)255).putShort((short)rightMm).putShort((short)255).flip();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,r.depthTex);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RG16UI,2,1,0,GLES30.GL_RG_INTEGER,GLES30.GL_UNSIGNED_SHORT,depth);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        r.depthReady=true;r.depthUvTransform=new float[]{1,0,0,0,1,0,0,0,1};
        GLES30.glClearColor(.085f,.115f,.145f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);r.drawSplats();
        ByteBuffer pixels=ByteBuffer.allocateDirect(r.width*r.height*4);GLES30.glReadPixels(0,0,r.width,r.height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixels);
        leftPixels=rightPixels=0;int count=0;for(int i=0;i<r.width*r.height;i++){int k=i*4;if(Math.abs((pixels.get(k)&255)-22)+Math.abs((pixels.get(k+1)&255)-29)+Math.abs((pixels.get(k+2)&255)-37)>25){count++;if(i%r.width<r.width/2)leftPixels++;else rightPixels++;}}
        r.depthReady=false;return count;
    }
    void checkRayDepth(){
        MainActivity.ChairRenderer r=activity.renderer;float[] oldRows=r.rows,oldDepth=r.depth,oldVm=r.vm,oldProjection=r.projection,oldEye=r.eye;Integer[] oldOrder=r.order;FloatBuffer oldUpload=r.upload;int oldCount=r.count;
        try{
            r.count=1;r.rows=new float[37];r.rows[2]=-2;r.rows[3]=r.rows[4]=r.rows[5]=.04f;r.rows[7]=.035f;r.rows[12]=.98f;r.order=new Integer[]{0};r.depth=new float[1];r.upload=ByteBuffer.allocateDirect(148).order(ByteOrder.nativeOrder()).asFloatBuffer();r.vm=new float[16];android.opengl.Matrix.setIdentityM(r.vm,0);r.projection=new float[16];android.opengl.Matrix.perspectiveM(r.projection,0,60,(float)r.width/r.height,.03f,30);r.eye=new float[4];
            syntheticDepthPixels(2000,2000);check(Math.abs(leftPixels-rightPixels)>Math.max(leftPixels,rightPixels)*.3,"Tilted Gaussian uses ray-dependent depth, not constant centre depth");
        }finally{r.count=oldCount;r.rows=oldRows;r.depth=oldDepth;r.order=oldOrder;r.upload=oldUpload;r.vm=oldVm;r.projection=oldProjection;r.eye=oldEye;}
    }
    void check(boolean value,String name){if(!value)throw new AssertionError(name);results.append("PASS ").append(name).append('\n');}
    void gl(Runnable work)throws Exception{CountDownLatch done=new CountDownLatch(1);activity.surface.queueEvent(()->{work.run();done.countDown();});if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("GL timeout");}
    void nextFrames()throws Exception{long initial=activity.renderer.renderedFrames,deadline=SystemClock.uptimeMillis()+8000;while(activity.renderer.renderedFrames<initial+2&&SystemClock.uptimeMillis()<deadline)Thread.sleep(25);check(activity.renderer.renderedFrames>=initial+2,"Renderer produced frames after setting change");}
    void capture(String name)throws Exception{Thread.sleep(650);Bitmap image=getUiAutomation().takeScreenshot();if(image==null)throw new AssertionError("Screenshot missing");try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();}
    void pointer(long down,long now,int action,float distance){
        MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++){properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i].x=activity.surface.getWidth()*.5f+(i==0?-distance:distance)*.5f;coords[i].y=activity.surface.getHeight()*.5f;coords[i].pressure=1;coords[i].size=1;}
        MotionEvent event=MotionEvent.obtain(down,now,action,2,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);activity.surface.dispatchTouchEvent(event);event.recycle();
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        Intent intent=new Intent(getTargetContext(),MainActivity.class);intent.putExtra("model_id",ModelStore.BUILTIN);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=(MainActivity)startActivitySync(intent);waitForIdleSync();Thread.sleep(900);
        gl(()->{});check(activity.renderer.fatal==null,"GL shaders compiled and asset loaded");check(activity.renderer.count==9880,"Real 9880-Gaussian chair present");capture("test-preview.png");
        float initial=activity.renderer.yaw;
        runOnMainSync(()->{long now=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,160,420,0);activity.surface.dispatchTouchEvent(d);d.recycle();for(int i=1;i<=5;i++){MotionEvent m=MotionEvent.obtain(now,now+i*20,MotionEvent.ACTION_MOVE,160+i*55,420,0);activity.surface.dispatchTouchEvent(m);m.recycle();}MotionEvent u=MotionEvent.obtain(now,now+120,MotionEvent.ACTION_UP,435,420,0);activity.surface.dispatchTouchEvent(u);u.recycle();});
        gl(()->{});check(Math.abs(activity.renderer.yaw-initial)>.5,"One-finger rotation changes camera");capture("test-rotated.png");
        Thread.sleep(450);float distance=activity.renderer.distance;
        runOnMainSync(()->{long now=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,260,420,0);activity.surface.dispatchTouchEvent(d);d.recycle();pointer(now,now+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),150);for(int i=1;i<=6;i++)pointer(now,now+20+i*20,MotionEvent.ACTION_MOVE,150+i*35);pointer(now,now+170,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),360);MotionEvent u=MotionEvent.obtain(now,now+190,MotionEvent.ACTION_UP,180,420,0);activity.surface.dispatchTouchEvent(u);u.recycle();});
        gl(()->{});check(activity.renderer.distance<distance*.8,"Two-finger pinch zoom changes distance: "+distance+" -> "+activity.renderer.distance);capture("test-zoomed.png");
        // Exercise the reset button through its UI listener.
        runOnMainSync(()->{android.view.ViewGroup actions=(android.view.ViewGroup)activity.arButton.getParent();actions.getChildAt(0).performClick();});gl(()->{});
        check(activity.renderer.yaw==0&&activity.renderer.distance==2.1f,"Reset restores default view");
        StabilizerTests.run(this);
        float uniform=DimensionFit.scale(.5f,.7f,1,.6f,.84f,1.2f);check(Math.abs(uniform-1.2f)<1e-5,"Three measurements fit one scale without changing shape");
        gl(()->{activity.renderer.placementLocked=true;activity.renderer.drag(100,100);activity.renderer.scale(2);activity.renderer.requestPlacement(100,100);});
        check(activity.renderer.yaw==0&&activity.renderer.distance==2.1f&&activity.renderer.tap==null,"Locked model rejects rotation/scale/reposition gestures");
        boolean lockRejected=false;try{activity.applyDimensions(.7f,.5f,1.2f,false);}catch(IllegalStateException e){lockRejected=true;}check(lockRejected,"Locked model rejects dimension changes");gl(()->activity.renderer.reset());
        runOnMainSync(()->activity.applyDimensions(.7f,.5f,1.2f,false));nextFrames();gl(()->{});
        check(Math.abs(activity.renderer.model[0]-.5f/activity.nominalWidth)<.0001,"Width applies metric scale");
        check(Math.abs(activity.renderer.model[5]-1.2f/activity.nominalHeight)<.0001,"Height applies metric scale");
        check(Math.abs(activity.renderer.model[10]-.7f/activity.nominalDepth)<.0001,"Length applies metric scale");capture("test-dimensions.png");
        runOnMainSync(()->activity.showSizeGuide=true);capture("test-size-guide.png");runOnMainSync(()->activity.showSizeGuide=false);
        runOnMainSync(()->activity.showDimensions(null));capture("test-dimensions-dialog.png");sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
        runOnMainSync(()->activity.applyDimensions(.7f,.5f,1,true));gl(()->{});
        check(Math.abs(activity.requestedWidth-activity.nominalWidth)<.0001&&Math.abs(activity.requestedDepth-activity.nominalDepth)<.0001,"Keep-ratio mode uses only measured height");
        runOnMainSync(()->{activity.applyDimensions(.7f,.5f,.8f,true);activity.loadDimensions();});
        check(activity.dimensionsConfirmed&&Math.abs(activity.requestedHeight-.8f)<.0001,"Same model preserves user-confirmed measured size");
        runOnMainSync(()->{activity.getPreferences(0).edit().putString("assetId","another-furniture").apply();activity.loadDimensions();});
        check(!activity.dimensionsConfirmed&&Math.abs(activity.requestedHeight-activity.nominalHeight)<.0001,"Different model resets inherited size and requires confirmation");
        runOnMainSync(()->{activity.getPreferences(0).edit().remove("assetId").apply();activity.applyDimensions(.7f,.5f,.8f,true);activity.loadDimensions();});
        check(activity.dimensionsConfirmed&&Math.abs(activity.requestedHeight-.8f)<.0001,"Legacy chair install keeps its measured size on upgrade");
        runOnMainSync(()->activity.applyDimensions(.7f,.5f,1,true));nextFrames();
        boolean rejected=false;try{MainActivity.centimetres("NaN");}catch(Exception e){rejected=true;}check(rejected,"Invalid dimensions rejected");
        final int[] pixels=new int[4];gl(()->{pixels[0]=syntheticDepthPixels(7000,7000);pixels[1]=syntheticDepthPixels(300,300);pixels[2]=syntheticDepthPixels(300,7000);pixels[3]=syntheticDepthPixels(0,0);});
        check(pixels[0]>1000,"Far real surface leaves chair visible");check(pixels[1]<pixels[0]*.02,"Near real surface occludes chair");check(pixels[2]>pixels[0]*.1&&pixels[2]<pixels[0]*.85,"Per-pixel occluder hides only part of chair");check(Math.abs(pixels[3]-pixels[0])<pixels[0]*.02,"Missing depth does not hide chair");
        check(!MainActivity.EXPERIMENTAL_DEPTH,"Regressing fusion/ray-depth path disabled for phone baseline");
        gl(()->{syntheticDepthPixels(1000,4000);activity.renderer.drawDepthMap();ByteBuffer b=ByteBuffer.allocateDirect(activity.renderer.width*activity.renderer.height*4);GLES30.glReadPixels(0,0,activity.renderer.width,activity.renderer.height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,b);java.util.HashSet<Integer> colours=new java.util.HashSet<>();int row=activity.renderer.height/2;for(int x=0;x<activity.renderer.width;x++){int k=(row*activity.renderer.width+x)*4;colours.add(((b.get(k)&255)<<16)|((b.get(k+1)&255)<<8)|(b.get(k+2)&255));}check(colours.size()>32,"Depth showcase interpolates colours smoothly instead of nearest-neighbour blocks");});
        ByteBuffer input=ByteBuffer.allocate(24);input.position(2);input.put(2,(byte)1);input.put(3,(byte)2);input.put(6,(byte)3);input.put(7,(byte)4);input.put(14,(byte)5);input.put(15,(byte)6);input.put(18,(byte)7);input.put(19,(byte)8);ByteBuffer output=ByteBuffer.allocate(8);DepthUpload.copy(input,2,2,12,4,output);for(int i=0;i<8;i++)check(output.get(i)==i+1,"Depth row/pixel stride byte "+i);
        runOnMainSync(()->activity.startAR());waitForIdleSync();Thread.sleep(1500);
        for(int i=0;i<4&&!activity.hasWindowFocus();i++){sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(650);}
        waitForIdleSync();Thread.sleep(700);
        check(activity.hasWindowFocus(),"Unavailable AR returns to preview window");
        check(activity.session==null,"Missing AR service does not start AR session");check(activity.renderer.fatal==null,"Missing AR service leaves preview renderer available");capture("test-ar-fallback.png");
        gl(()->{});check(activity.renderer.count==9880,"Chair remains available after AR check");
        boolean libraryFixture=false;try(java.io.InputStream fixture=getContext().getAssets().open("library-reference.json")){libraryFixture=true;}catch(java.io.IOException ignored){}
        if(libraryFixture)LibraryTests.run(this);
        result.putString("stream",results.toString());finish(android.app.Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",results+"FAIL "+error.toString());finish(android.app.Activity.RESULT_CANCELED,result);}}
}
