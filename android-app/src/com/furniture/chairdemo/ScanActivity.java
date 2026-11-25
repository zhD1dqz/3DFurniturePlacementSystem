package com.furniture.chairdemo;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.Image;
import android.opengl.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.json.*;

/** Capture-only milestone: no old-camera poses or old-chair masks used for new scans. */
public class ScanActivity extends Activity implements GLSurfaceView.Renderer {
    static final int MAX_FRAMES=300;
    static final long MAX_CAPTURE_MS=180000;
    GLSurfaceView surface;TextView status;Button record;volatile Session session;
    volatile boolean recording=false,ending=false;boolean installRequested=false,resumed=false,permissionRequested=false;
    final ExecutorService writer=Executors.newSingleThreadExecutor();final AtomicBoolean busy=new AtomicBoolean();
    File folder;JSONObject manifest;volatile int saved;long started,lastTimestamp,lastImageTimestamp,lastStatus;float[] lastPose;
    int texture,program,width=1,height=1;FloatBuffer quad,uv;int unavailable,lost,duplicate,still,unsynced;long lastFrameTime,lastCameraTime,lastImageTime;String lastSkip="";
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    void text(String s){runOnUiThread(()->status.setText(s));}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        LinearLayout header=Ui.toolbar(this,"扫描家具",this::onBackPressed);
        header.setPadding(0,0,dp(14),0);
        Button history=Ui.button(this,"采集记录",false);Ui.icon(history,StudioIcon.Kind.HISTORY,false);
        history.setOnClickListener(v->{if(recording||ending){text("请先结束并等待保存完成");return;}showHistory();});
        header.addView(history,new LinearLayout.LayoutParams(-2,dp(40)));root.addView(header);Ui.divider(root);
        TextView guide=Ui.text(this,"让整件家具入镜，缓慢绕拍，再补拍高、低视角。",13,Ui.MUTED);
        guide.setPadding(dp(18),dp(12),dp(18),dp(10));root.addView(guide);
        FrameLayout viewport=new FrameLayout(this);viewport.setBackground(Ui.surface(this,Ui.PANEL,20));viewport.setClipToOutline(true);
        LinearLayout.LayoutParams cameraSlot=new LinearLayout.LayoutParams(-1,0,1);cameraSlot.setMargins(dp(12),0,dp(12),0);root.addView(viewport,cameraSlot);
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setPreserveEGLContextOnPause(true);surface.setRenderer(this);
        viewport.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        viewport.addView(new FramingGuide(this),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout footer=Ui.column(this);footer.setPadding(dp(14),dp(10),dp(14),dp(12));footer.setBackground(Ui.surface(this,Ui.PANEL,18));
        LinearLayout.LayoutParams controls=new LinearLayout.LayoutParams(-1,-2);controls.setMargins(dp(12),dp(10),dp(12),dp(12));root.addView(footer,controls);
        status=Ui.text(this,"正在准备相机…",14,Ui.MUTED);status.setPadding(dp(2),dp(2),dp(2),dp(8));footer.addView(status);
        record=Ui.button(this,"开始采集",true);record.setOnClickListener(v->{if(recording)finishCapture("completed");else begin();});footer.addView(record,new LinearLayout.LayoutParams(-1,dp(50)));
        setContentView(root);WindowLayout.safe(root);
    }
    private static final class FramingGuide extends View {
        private final Paint outline=new Paint(Paint.ANTI_ALIAS_FLAG);
        FramingGuide(ScanActivity host){super(host);outline.setStrokeWidth(Ui.dp(host,2));outline.setStrokeCap(Paint.Cap.ROUND);outline.setColor(0xbbd0f1ce);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float left=getWidth()*.17f,right=getWidth()-left,top=getHeight()*.22f,bottom=getHeight()-top;
            float length=Math.min(Ui.dp(getContext(),22),Math.min(right-left,bottom-top)*.15f);
            for(float x:new float[]{left,right})for(float y:new float[]{top,bottom}){
                canvas.drawLine(x,y,x+(x==left?length:-length),y,outline);
                canvas.drawLine(x,y,x,y+(y==top?length:-length),outline);
            }
        }
    }
    void openCamera(){if(!resumed||session!=null)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            if(!permissionRequested){permissionRequested=true;requestPermissions(new String[]{Manifest.permission.CAMERA},8);}
            return;
        }
        Session created=null;try{
            if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installRequested=true;text("请完成 AR 服务安装");return;}
            created=new Session(this);selectDetailCamera(created);Config config=new Config(created);config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);config.setFocusMode(Config.FocusMode.AUTO);config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);created.configure(config);
            surface.onPause();created.resume();session=created;surface.onResume();text("请缓慢移动手机，定位稳定后开始采集");
        }catch(Exception error){if(created!=null)created.close();surface.onResume();text("相机暂不可用："+error.getClass().getSimpleName()+"。可返回预览或重新进入。");}
    }
    void selectDetailCamera(Session s)throws Exception{
        CameraConfigFilter filter=new CameraConfigFilter(s).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));
        List<CameraConfig> configs=s.getSupportedCameraConfigs(filter);CameraConfig best=null;long area=0;JSONArray available=new JSONArray();
        for(CameraConfig c:configs){android.util.Size size=c.getImageSize();long pixels=(long)size.getWidth()*size.getHeight();available.put(new JSONObject().put("width",size.getWidth()).put("height",size.getHeight()).put("texture_size",c.getTextureSize().toString()));
            if(c.getFacingDirection()==CameraConfig.FacingDirection.BACK&&pixels<=1920L*1440&&pixels>area){best=c;area=pixels;}}
        if(best!=null)s.setCameraConfig(best);android.util.Size size=s.getCameraConfig().getImageSize();
        CaptureData.atomic(new File(getFilesDir(),"capture-camera-config.json"),new JSONObject().put("available_30fps",available).put("selected_width",size.getWidth()).put("selected_height",size.getHeight()).put("stream","CPU image, not GPU preview"));
    }
    @Override protected void onResume(){super.onResume();resumed=true;surface.onResume();if(session==null)openCamera();else try{session.resume();}catch(Exception e){text("相机恢复失败，请返回后重新进入");}}
    @Override protected void onPause(){resumed=false;finishCapture("interrupted");surface.onPause();Session s=session;if(s!=null)s.pause();super.onPause();}
    @Override protected void onDestroy(){Session s=session;session=null;if(s!=null)s.close();writer.shutdown();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] result){super.onRequestPermissionsResult(code,permissions,result);if(code==8){if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED)openCamera();else text("未开启相机权限，可以返回已有模型");}}
    @Override public void onBackPressed(){if(ending){text("正在保存，请稍候");return;}if(recording)StudioDialog.confirm(this,"结束本次采集？",StudioIcon.Kind.SCAN,"已拍画面会保留，可在采集记录中查看。","继续拍摄","结束并返回",false,()->{finishCapture("interrupted");finish();});else super.onBackPressed();}
    void begin(){if(session==null||ending){text("请等待相机准备完成");return;}
        surface.queueEvent(()->{if(recording||ending)return;try{
            saved=0;lastTimestamp=0;lastImageTimestamp=0;lastPose=null;unavailable=lost=duplicate=still=unsynced=0;
            folder=new File(getFilesDir(),"captures/scan-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8));if(!folder.mkdirs())throw new IOException("创建采集目录失败");
            manifest=new JSONObject().put("schema_version",3).put("id",folder.getName()).put("created_at_ms",System.currentTimeMillis()).put("state","recording").put("coordinate_contract","ARCore world meters; unrotated CPU image; c2w column-major; OpenCV c2w flips local Y/Z").put("image_rotation_degrees",0).put("camera_pose_source","ARCore physical camera pose; not COLMAP-refined").put("depth_saved",false).put("training_ready",false).put("timestamp_contract","SDK current Frame.acquireCameraImage pairing; frame and CPU image timestamps strictly advance independently; clock equality is not assumed").put("frames",0);
            android.util.Size size=session.getCameraConfig().getImageSize();manifest.put("capture_preset","detail-v012").put("cpu_width",size.getWidth()).put("cpu_height",size.getHeight()).put("max_frames",MAX_FRAMES).put("max_capture_ms",MAX_CAPTURE_MS);
            CaptureData.atomic(new File(folder,"capture.json"),manifest);started=SystemClock.elapsedRealtime();recording=true;
            runOnUiThread(()->record.setText("结束并保存"));text("已开始：缓慢绕拍，避免只站在原地转手机");
        }catch(Exception e){text("无法开始："+e.getMessage());}});
    }
    void finishCapture(String state){surface.queueEvent(()->{
        if(!recording)return;recording=false;ending=true;runOnUiThread(()->{record.setEnabled(false);record.setText("正在保存…");});
        final File target=folder;final JSONObject data=manifest;
        writer.execute(()->{String nameAfterSave=null;try{
            data.put("state",state).put("frames",saved).put("duration_ms",SystemClock.elapsedRealtime()-started).put("skipped_tracking",lost).put("skipped_duplicate",duplicate).put("skipped_still",still).put("skipped_unavailable",unavailable).put("skipped_timestamp_mismatch",unsynced).put("last_frame_timestamp_ns",lastFrameTime).put("last_android_camera_timestamp_ns",lastCameraTime).put("last_image_timestamp_ns",lastImageTime).put("last_skip_reason",lastSkip);
            if(saved<20)data.put("quality_note","Too few views; recapture recommended");else data.put("quality_note","Frame count only; coverage, blur and pose accuracy still require validation");
            CaptureData.atomic(new File(target,"capture.json"),data);text("已保存 "+saved+" 张画面。可在采集记录中查看或生成模型。");
            if("completed".equals(state))nameAfterSave=target.getName();
        }catch(Exception e){text("保存状态失败；已写入的画面保留："+e.getMessage());}finally{
            String id=nameAfterSave;
            ending=false;
            runOnUiThread(()->{
                record.setEnabled(true);record.setText("开始新的采集");
                if(id!=null&&resumed&&!isFinishing()&&!isDestroyed())nameCapture(id,true,null);
            });
        }});
    });}
    void nameCapture(String id,boolean justSaved,Runnable refresh){
        NameDialog.show(this,justSaved?"为拍摄记录命名":"重命名拍摄记录",justSaved?"":LibraryNames.capture(this,id),
                "例如：客厅、餐桌",justSaved?"稍后":"取消",false,name->{
            LibraryNames.renameCapture(this,id,name);
            if(justSaved)text("已保存「"+name+"」 · "+saved+" 张画面");
            else refresh.run();
        });
    }
    void showHistory(){
        StudioDialog page=StudioDialog.page(this,"采集记录");
        fillHistory(page);page.show();
    }
    private void fillHistory(StudioDialog page){
        page.body.removeAllViews();
        List<JSONObject> all=CaptureData.sessions(getFilesDir());
        if(all.isEmpty())page.empty("还没有采集记录","完成一次扫描后，拍摄记录会出现在这里。");
        for(JSONObject item:all){
            String id=item.optString("id"),name=LibraryNames.capture(this,id);
            int count=item.optInt("committed_frames");
            String state="completed".equals(item.optString("state"))?"已保存":"中断保留";
            page.choice(StudioIcon.Kind.SCAN,name,count+" 张画面 · "+state,()->{
                StudioDialog detail=StudioDialog.panel(this,name,StudioIcon.Kind.SCAN);
                detail.message(count+" 张画面 · "+state+(count<20?"\n生成模型至少需要 20 张有效画面。":""));
                detail.actions("重命名",()->{detail.dismiss();nameCapture(id,false,()->fillHistory(page));},"生成模型",
                        count<20?null:()->{detail.dismiss();FurnitureLibrary.chooseGeneration(this,id);},false);
                detail.show();
            });
        }
    }
    void collect(Frame frame,com.google.ar.core.Camera camera){
        if(!recording||ending||busy.get())return;
        if(SystemClock.elapsedRealtime()-started>=MAX_CAPTURE_MS||saved>=MAX_FRAMES){finishCapture("completed");return;}
        if(camera.getTrackingState()!=TrackingState.TRACKING){lost++;return;}
        long ts=frame.getTimestamp();lastFrameTime=ts;if(ts<=lastTimestamp){duplicate++;return;}if(lastTimestamp>0&&ts-lastTimestamp<500000000L)return;
        float[] pose=new float[16];camera.getPose().toMatrix(pose,0);if(!CaptureData.moved(lastPose,pose)){still++;return;}
        if(getFilesDir().getUsableSpace()<100L*1024*1024){text("剩余空间不足，正在结束保存");finishCapture("interrupted");return;}
        try(Image image=frame.acquireCameraImage()){
            lastCameraTime=frame.getAndroidCameraTimestamp();lastImageTime=image.getTimestamp();
            if(!CaptureData.freshImage(ts,lastTimestamp,lastImageTime,lastImageTimestamp)){duplicate++;lastSkip="duplicate_camera_image";return;}
            CameraIntrinsics k=camera.getImageIntrinsics();int[] size=k.getImageDimensions();int w=image.getWidth(),h=image.getHeight();
            if(size[0]!=w||size[1]!=h||image.getCropRect().left!=0||image.getCropRect().top!=0||image.getCropRect().width()!=w||image.getCropRect().height()!=h)throw new IOException("CPU image geometry mismatch");
            Image.Plane[] planes=image.getPlanes();ByteBuffer[] buffers=new ByteBuffer[3];int[] strides=new int[3],pixels=new int[3];for(int i=0;i<3;i++){buffers[i]=planes[i].getBuffer();strides[i]=planes[i].getRowStride();pixels[i]=planes[i].getPixelStride();}
            byte[] yuv=CaptureData.nv21(w,h,buffers,strides,pixels);final int index=saved;final File target=folder;
            JSONObject meta=new JSONObject().put("image",String.format(Locale.US,"frame-%04d.jpg",index)).put("frame_timestamp_ns",ts).put("pairing_method","ARCore.Frame.acquireCameraImage/current-update").put("image_minus_android_camera_ns",lastImageTime-lastCameraTime).put("frame_minus_image_ns",ts-lastImageTime).put("android_camera_timestamp_ns",lastCameraTime).put("image_timestamp_ns",image.getTimestamp()).put("width",w).put("height",h).put("focal_length_px",CaptureData.array(k.getFocalLength())).put("principal_point_px",CaptureData.array(k.getPrincipalPoint())).put("camera_to_world_gl_column_major",CaptureData.array(pose)).put("camera_to_world_cv_column_major",CaptureData.array(CaptureData.cvPose(pose))).put("tracking_state","TRACKING");
            try(PointCloud cloud=frame.acquirePointCloud()){
                JSONObject filtered=CaptureData.pointCloud(cloud.getPoints(),cloud.getTimestamp());java.util.Iterator<String> keys=filtered.keys();while(keys.hasNext()){String key=keys.next();meta.put(key,filtered.get(key));}
            }catch(Exception optionalError){
                // Camera pose/intrinsics were already checked above. Missing sparse
                // geometry must not discard an otherwise usable camera image.
                meta.put("point_cloud_world_xyzc",new JSONArray()).put("point_cloud_status","unavailable").put("point_cloud_error",optionalError.getClass().getSimpleName());
            }
            busy.set(true);lastTimestamp=ts;lastImageTimestamp=lastImageTime;lastPose=pose;
            writer.execute(()->{try{
                File jpeg=new File(target,meta.getString("image"));try(FileOutputStream out=new FileOutputStream(jpeg)){if(!new YuvImage(yuv,android.graphics.ImageFormat.NV21,w,h,null).compressToJpeg(new Rect(0,0,w,h),95,out))throw new IOException("JPEG encoding failed");out.getFD().sync();}
                meta.put("image_sha256",PlyModelImporter.sha(jpeg));CaptureData.atomic(new File(target,String.format(Locale.US,"frame-%04d.json",index)),meta);saved++;
            }catch(Exception e){finishCapture("failed");text("采集已停止，已保存画面保留："+e.getMessage());}finally{busy.set(false);}});
        }catch(NotYetAvailableException e){unavailable++;}catch(Exception e){lastSkip=e.toString();android.util.Log.w("FurnitureCapture","Skipped image",e);text("跳过此帧："+e.getClass().getSimpleName());}
    }
    static FloatBuffer floats(float[] a){FloatBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(a).position(0);return b;}
    static int shader(int type,String source){int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,source);GLES30.glCompileShader(s);int[] ok={0};GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES30.glGetShaderInfoLog(s));return s;}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        int[] ids={0};GLES30.glGenTextures(1,ids,0);texture=ids[0];GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        program=GLES30.glCreateProgram();GLES30.glAttachShader(program,shader(GLES30.GL_VERTEX_SHADER,"#version 300 es\nlayout(location=0) in vec2 p;layout(location=1) in vec2 t;out vec2 uv;void main(){gl_Position=vec4(p,0,1);uv=t;}"));GLES30.glAttachShader(program,shader(GLES30.GL_FRAGMENT_SHADER,"#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;uniform samplerExternalOES cameraTexture;in vec2 uv;out vec4 color;void main(){color=texture(cameraTexture,uv);}"));GLES30.glLinkProgram(program);
        quad=floats(new float[]{-1,-1,1,-1,-1,1,1,1});uv=floats(new float[8]);
    }
    @Override public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;GLES30.glViewport(0,0,w,h);}
    @Override public void onDrawFrame(GL10 gl){GLES30.glClearColor(.07f,.1f,.13f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);Session s=session;if(s==null)return;
        try{s.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(),width,height);s.setCameraTextureName(texture);Frame frame=s.update();
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,quad,Coordinates2d.TEXTURE_NORMALIZED,uv);quad.position(0);uv.position(0);
            GLES30.glUseProgram(program);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"cameraTexture"),0);GLES30.glEnableVertexAttribArray(0);GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,0,quad);GLES30.glVertexAttribPointer(1,2,GLES30.GL_FLOAT,false,0,uv);GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4);
            com.google.ar.core.Camera camera=frame.getCamera();collect(frame,camera);long now=SystemClock.elapsedRealtime();if(now-lastStatus>800&&!ending){lastStatus=now;android.util.Size size=s.getCameraConfig().getImageSize();String resolution=size.getWidth()+"×"+size.getHeight();if(recording)text("已保存 "+saved+" / "+MAX_FRAMES+" 张 · "+resolution+"\n"+(camera.getTrackingState()==TrackingState.TRACKING?"缓慢绕拍，补拍不同高度":"定位丢失，已暂停取帧；请缓慢移动恢复"));else if(saved==0)text(camera.getTrackingState()==TrackingState.TRACKING?"定位正常 · "+resolution+"，可以开始采集":"请对准有纹理的家具，缓慢移动手机");}
        }catch(Exception error){if(recording)finishCapture("interrupted");text("相机暂停："+error.getClass().getSimpleName()+"，请返回后重试");}
    }
}
