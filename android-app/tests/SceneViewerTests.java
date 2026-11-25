package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;
import org.json.*;

/** Exercise the actual scene renderer and touch events without training or changing furniture sizes. */
public class SceneViewerTests extends SmokeTests {
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();String active=ModelStore.active(context);activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        float height=activity.requestedHeight;String id=SceneStore.prepare(context,SceneStore.latestTask(context));JSONObject meta=SceneStore.load(context,id);
        check(id.equals(meta.getString("source_ply_sha256")),"Scene identity is the actual phone-generated PLY hash");
        SceneViewerActivity scene=(SceneViewerActivity)startActivitySync(new Intent(context,SceneViewerActivity.class).putExtra("scene_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=scene;waitForIdleSync();Thread.sleep(700);gl(()->{});nextFrames();
        check(scene.renderer.fatal==null&&scene.renderer.count==meta.getInt("gaussians"),"Actual Gaussian scene loads in GLES renderer");
        gl(()->{scene.renderer.onDrawFrame(null);java.nio.ByteBuffer pixels=java.nio.ByteBuffer.allocateDirect(scene.renderer.width*scene.renderer.height*4);android.opengl.GLES30.glReadPixels(0,0,scene.renderer.width,scene.renderer.height,android.opengl.GLES30.GL_RGBA,android.opengl.GLES30.GL_UNSIGNED_BYTE,pixels);double sum=0,square=0;int n=0;for(int i=0;i<pixels.capacity();i+=512){double value=((pixels.get(i)&255)+(pixels.get(i+1)&255)+(pixels.get(i+2)&255))/3.0;sum+=value;square+=value*value;n++;}check(square/n-(sum/n)*(sum/n)>25,"Initial live viewport contains scene detail, not an opaque flat splat");});
        check(scene.renderer.count!=9880&&scene.renderer.count>100,"Displayed data is the newly trained scene, not the original chair");
        check(scene.session==null&&scene.arButton==null,"Full scene viewer has no furniture-size or AR controls");final float[] defaultCamera=new float[2];gl(()->{scene.sceneRenderer.reset();defaultCamera[0]=scene.renderer.yaw;defaultCamera[1]=scene.renderer.distance;});capture("scene-initial.png");
        float yaw=defaultCamera[0];runOnMainSync(()->{long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,200,400,0);scene.surface.dispatchTouchEvent(down);down.recycle();MotionEvent move=MotionEvent.obtain(t,t+40,MotionEvent.ACTION_MOVE,380,440,0);scene.surface.dispatchTouchEvent(move);move.recycle();MotionEvent up=MotionEvent.obtain(t,t+80,MotionEvent.ACTION_UP,380,440,0);scene.surface.dispatchTouchEvent(up);up.recycle();});gl(()->{});nextFrames();check(Math.abs(scene.renderer.yaw-yaw)>.5,"Single-finger drag rotates the live camera");capture("scene-rotated.png");
        float distance=scene.renderer.distance;runOnMainSync(()->{long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,200,400,0);scene.surface.dispatchTouchEvent(down);down.recycle();pointer(t,t+10,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),140);for(int i=1;i<=6;i++)pointer(t,t+10+i*30,MotionEvent.ACTION_MOVE,140+i*30);pointer(t,t+220,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),320);MotionEvent up=MotionEvent.obtain(t,t+240,MotionEvent.ACTION_UP,200,400,0);scene.surface.dispatchTouchEvent(up);up.recycle();});gl(()->{});check(scene.renderer.distance<distance*.9,"Two-finger pinch changes orbit distance");
        float[] pivot=scene.sceneRenderer.pivot.clone();gl(()->scene.sceneRenderer.pan(80,25));nextFrames();double delta=0;for(int i=0;i<3;i++)delta+=Math.abs(pivot[i]-scene.sceneRenderer.pivot[i]);check(delta>.001,"Pan changes camera target without modifying model coordinates");
        gl(()->scene.sceneRenderer.reset());check(Math.abs(scene.sceneRenderer.distance-defaultCamera[1])<.001&&Math.abs(scene.sceneRenderer.yaw-defaultCamera[0])<.001,"Reset restores initial camera and target: distance="+scene.sceneRenderer.distance+" expected="+distance+" yaw="+scene.sceneRenderer.yaw+" expectedYaw="+yaw);gl(()->scene.sceneRenderer.whole());nextFrames();capture("scene-whole.png");check(scene.renderer.distance>distance,"Whole-scene view fits scene bounds");
        check(active.equals(ModelStore.active(context)),"Scene viewing leaves selected furniture unchanged");
        JSONObject report=new JSONObject().put("scene",meta).put("passed",results.toString());ModelStore.write(new File(context.getFilesDir(),"scene-viewer-test.json"),report);
        SceneViewerActivity again=(SceneViewerActivity)startActivitySync(new Intent(context,SceneViewerActivity.class).putExtra("scene_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=again;waitForIdleSync();Thread.sleep(500);gl(()->{});check(again.renderer.fatal==null&&again.renderer.count==meta.getInt("gaussians"),"Converted scene reopens without training again");
        MainActivity original=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).putExtra("model_id",active).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activity=original;waitForIdleSync();Thread.sleep(500);gl(()->{});check(original.renderer.fatal==null&&Math.abs(original.requestedHeight-height)<.0001,"Original renderer and measured dimensions remain available");
        result.putString("stream",results.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",results+"FAIL "+error);finish(Activity.RESULT_CANCELED,result);}}
}
