package com.furniture.chairdemo;
import android.os.Bundle;
import android.opengl.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import org.json.*;

/** Real Gaussian rendering from the phone-generated file. No evaluation PNGs loaded. */
public class SceneViewerActivity extends MainActivity {
    static void open(android.app.Activity activity,File task){
        StudioDialog progress=StudioDialog.progress(activity,"打开三维模型","正在准备预览…");
        new Thread(()->{try{File selected=task==null?SceneStore.latestTask(activity):task;String id=SceneStore.prepare(activity,selected);activity.runOnUiThread(()->{progress.dismiss();if(!activity.isFinishing())activity.startActivity(new android.content.Intent(activity,SceneViewerActivity.class).putExtra("scene_id",id).putExtra("scene_task",selected.getName()));});}catch(Exception error){activity.runOnUiThread(()->{progress.dismiss();if(!activity.isFinishing())StudioDialog.confirm(activity,"模型未能打开",null,error.getMessage(),null,"知道了",false,()->{});});}},"PrepareScene").start();
    }
    String sceneId,editedFurnitureName="";JSONObject scene;float[] focus,initialEye,wholeCenter;float radius;SceneRenderer sceneRenderer;CropEditor cropEditor;
    private String sceneTitle(){
        String task=getIntent().getStringExtra("scene_task");
        if(task==null)task=scene.optString("source_task");
        if(!task.matches("probe-\\d+-1200"))return "三维场景";
        try{
            JSONObject result=ModelStore.json(new File(getFilesDir(),task+"/integration-result.json"));
            return LibraryNames.scene(this,task,result.optString("capture_id"),result.optString("source"));
        }catch(Exception unavailable){return "三维场景";}
    }
    @Override protected boolean initializeViewer(Bundle state){
        sceneId=getIntent().getStringExtra("scene_id");activeModelId="scene-"+sceneId;try{
            scene=SceneStore.load(this,sceneId);focus=vector("focus");initialEye=vector("initial_eye");wholeCenter=vector("center");radius=(float)scene.getDouble("radius");
            nominalWidth=nominalDepth=nominalHeight=requestedWidth=requestedDepth=requestedHeight=1;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
            LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
            root.addView(Ui.toolbar(this,sceneTitle(),this::finish));
            TextView note=Ui.text(this,"单指旋转，双指缩放或平移",13,Ui.MUTED);note.setPadding(dp(16),0,dp(16),dp(7));root.addView(note);
            surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setPreserveEGLContextOnPause(true);sceneRenderer=new SceneRenderer();renderer=sceneRenderer;sceneRenderer.reset();surface.setRenderer(renderer);root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout footer=Ui.column(this);footer.setPadding(dp(12),dp(5),dp(12),dp(8));root.addView(footer);
            status=Ui.text(this,"裁剪后可单独保存家具。",13,Ui.MUTED);status.setPadding(dp(5),dp(4),dp(5),dp(4));footer.addView(status);
            cropEditor=new CropEditor(this);footer.addView(cropEditor.panel);String edit=getIntent().getStringExtra("edit_crop_id");if(edit!=null){JSONObject saved=ModelStore.metadata(this,edit);if(!sceneId.equals(saved.getString("source_scene_id")))throw new IOException("裁剪与场景不匹配");editedFurnitureName=saved.optString("name");cropEditor.box=CropBox.fromJson(saved.getJSONObject("crop"));cropEditor.draft=true;cropEditor.rebuild();cropEditor.panel.setVisibility(View.VISIBLE);cropEditor.preview.setChecked(true);}
            LinearLayout actions=new LinearLayout(this);footer.addView(actions);
            Button reset=Ui.plain(this,"重置视角");reset.setOnClickListener(v->surface.queueEvent(()->sceneRenderer.reset()));actions.addView(reset,new LinearLayout.LayoutParams(0,dp(48),1));
            Button whole=Ui.plain(this,"整个场景");whole.setOnClickListener(v->{if(cropEditor!=null&&cropEditor.preview.isChecked())cropEditor.preview.setChecked(false);surface.queueEvent(()->sceneRenderer.whole());});actions.addView(whole,new LinearLayout.LayoutParams(0,dp(48),1));
            Button crop=Ui.button(this,"裁剪家具",true);crop.setOnClickListener(v->cropEditor.open());actions.addView(crop,new LinearLayout.LayoutParams(0,dp(48),1));
            setContentView(root);WindowLayout.safe(root);
            float[] last={0,0},lastSpan={0};boolean[] multiple={false};surface.setOnTouchListener((v,e)->{
                int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){multiple[0]=false;last[0]=e.getX();last[1]=e.getY();}
                if(e.getPointerCount()>=2){float x=(e.getX(0)+e.getX(1))*.5f,y=(e.getY(0)+e.getY(1))*.5f,span=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));if(action==MotionEvent.ACTION_MOVE&&multiple[0]){float dx=x-last[0],dy=y-last[1],factor=lastSpan[0]>10&&span>10?span/lastSpan[0]:1;surface.queueEvent(()->{sceneRenderer.scale(factor);sceneRenderer.pan(dx,dy);});}last[0]=x;last[1]=y;lastSpan[0]=span;multiple[0]=true;}
                else if(action==MotionEvent.ACTION_MOVE&&!multiple[0]){float dx=e.getX()-last[0],dy=e.getY()-last[1];last[0]=e.getX();last[1]=e.getY();surface.queueEvent(()->sceneRenderer.drag(dx,dy));}
                return true;
            });
        }catch(Exception error){TextView failure=new TextView(this);failure.setText("模型未能打开："+error.getMessage()+"\n请返回后重试，原模型仍保留。");setContentView(failure);WindowLayout.safe(failure);}
        return true;
    }
    @Override void startAR(){message("请先提取独立家具并校准尺寸，再接入 AR。");}
    float[] vector(String key)throws Exception{double[] data=PlyModelImporter.vector(scene.getJSONArray(key));float[] v=new float[data.length];for(int i=0;i<v.length;i++)v[i]=(float)data[i];return v;}
    @Override protected InputStream rendererInput()throws Exception{return new FileInputStream(new File(SceneStore.folder(this,sceneId),"scene.bin"));}
    @Override protected void previewCamera(float[] view,float[] projection,int w,int h,float yaw,float pitch,float distance){
        float[] pivot=sceneRenderer.pivot;float ex=pivot[0]+(float)(distance*Math.sin(yaw)*Math.cos(pitch)),ey=pivot[1]+(float)(distance*Math.sin(pitch)),ez=pivot[2]+(float)(distance*Math.cos(yaw)*Math.cos(pitch));
        Matrix.setLookAtM(view,0,ex,ey,ez,pivot[0],pivot[1],pivot[2],0,1,0);Matrix.perspectiveM(projection,0,42,(float)w/h,.02f,Math.max(50,radius*30));
    }
    class SceneRenderer extends ChairRenderer {
        float[] sourceRows;CropBox cropBox;boolean cropOnly;
        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,javax.microedition.khronos.egl.EGLConfig config){super.onSurfaceCreated(gl,config);if(fatal==null){sourceRows=rows.clone();if(cropBox!=null)crop(cropBox,cropOnly);}}
        void crop(CropBox selected,boolean only){cropBox=selected;cropOnly=only;if(sourceRows==null)return;rows=only&&selected!=null?CropBox.select(sourceRows,selected):sourceRows;count=rows.length/37;depth=new float[count];order=new Integer[count];for(int i=0;i<count;i++)order[i]=i;upload=java.nio.ByteBuffer.allocateDirect(rows.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();message("当前显示 "+count+" 个高斯"+(selected!=null?" · 范围可调整，原场景保留":" · 完整场景"));}
        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){super.onDrawFrame(gl);if(cropBox==null||fatal!=null)return;float[] mvp=new float[16];Matrix.multiplyMM(mvp,0,projection,0,vm,0);GLES30.glUseProgram(lineProgram);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram,"mvp"),1,false,mvp,0);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,buffer(cropBox.lines()));GLES30.glLineWidth(2);GLES30.glDrawArrays(GLES30.GL_LINES,0,24);GLES30.glDisableVertexAttribArray(0);}

        float[] pivot=new float[3];
        @Override void reset(){System.arraycopy(focus,0,pivot,0,3);float x=initialEye[0]-pivot[0],y=initialEye[1]-pivot[1],z=initialEye[2]-pivot[2];distance=Math.max(.2f,(float)Math.sqrt(x*x+y*y+z*z));yaw=(float)Math.atan2(x,z);pitch=(float)Math.asin(Math.max(-.98,Math.min(.98,y/distance)));}
        @Override void scale(float f){if(Float.isFinite(f)&&f>0)distance=Math.max(.05f,Math.min(radius*15,distance/f));}
        @Override void drag(float dx,float dy){yaw-=dx*.006f;pitch=Math.max(-1.45f,Math.min(1.45f,pitch+dy*.004f));}
        void pan(float dx,float dy){float step=2*distance*(float)Math.tan(Math.toRadians(21))/Math.max(1,height);float sy=(float)Math.sin(yaw),cy=(float)Math.cos(yaw),sp=(float)Math.sin(pitch),cp=(float)Math.cos(pitch);pivot[0]+=step*(-dx*cy-dy*sy*sp);pivot[1]+=step*dy*cp;pivot[2]+=step*(dx*sy-dy*cy*sp);}
        void whole(){System.arraycopy(wholeCenter,0,pivot,0,3);float t=(float)Math.tan(Math.toRadians(21))*Math.min(1,(float)width/Math.max(1,height));distance=radius*(float)Math.sqrt(1+1/(t*t))*1.1f;pitch=.15f;}
    }
}
