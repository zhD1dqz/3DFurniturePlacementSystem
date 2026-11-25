package com.furniture.chairdemo;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Image;
import android.opengl.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {
    GLSurfaceView surface;
    ChairRenderer renderer;
    TextView status;
    Button arButton;
    Button lockButton;
    TextView dimensionSummary;
    String activeModelId=ModelStore.BUILTIN,startupNote;
    org.json.JSONObject activeModel;
    float nominalWidth=.6f,nominalDepth=.6f,nominalHeight=1;
    volatile float requestedWidth=.6f,requestedDepth=.6f,requestedHeight=1;
    boolean dimensionsConfirmed=false;
    volatile boolean depthSupported=false;
    volatile boolean showSizeGuide=false,showDepthMap=false;
    volatile boolean smoothOcclusionEdges=true;
    GroundPanel groundPanel;
    MainScreen ui;
    volatile boolean roomMode=false;
    volatile boolean showGroundGuide=false;
    volatile float groundGuideY=0,contactOffsetM=0;
    // Keep experimental fusion/ray-depth out of the default phone path after
    // the user's real-world test showed a visual regression.
    static final boolean EXPERIMENTAL_DEPTH=false;
    String sizeMode="height";
    volatile Session session;
    boolean installRequested=false, paused=true, pendingAR=false,preparingRoom=false;
    ArrayList<RoomScene.Furniture> preparedLayout;
    volatile long lastStatus,statusHoldUntil;
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(initializeViewer(state))return;
        activeModelId=getIntent().getStringExtra("model_id");if(activeModelId==null)activeModelId=ModelStore.active(this);
        try{activeModel=ModelStore.metadata(this,activeModelId);ModelStore.activate(this,activeModelId);}catch(Exception error){
            android.util.Log.e("ChairDemo","Selected model unavailable",error);activeModelId=ModelStore.BUILTIN;
            try{activeModel=ModelStore.metadata(this,activeModelId);ModelStore.activate(this,activeModelId);}catch(Exception ignored){}
            startupNote="保存的模型暂时无法打开，已恢复原椅子。";
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui=new MainScreen(this);pendingAR=getIntent().getBooleanExtra("room_mode",false);
        GestureDetector gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(android.view.MotionEvent e){return true;}
            @Override public boolean onSingleTapUp(android.view.MotionEvent e){float x=e.getX(),y=e.getY();if(session!=null)surface.queueEvent(()->renderer.requestPlacement(x,y));return true;}
            @Override public boolean onScroll(android.view.MotionEvent e,android.view.MotionEvent now,float dx,float dy){
                if(now.getPointerCount()==1){surface.queueEvent(()->renderer.drag(dx,dy));}return true;
            }
        });
        final boolean[] multiGesture={false};
        final float[] lastSpan={0};
        surface.setOnTouchListener((v,e)->{
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){multiGesture[0]=false;lastSpan[0]=0;}
            if(e.getPointerCount()>1){
                multiGesture[0]=true;
                float span=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
                if(action==MotionEvent.ACTION_MOVE&&lastSpan[0]>10&&span>10){float factor=span/lastSpan[0];surface.queueEvent(()->renderer.scale(factor));}
                lastSpan[0]=span;
            }
            if(action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)lastSpan[0]=0;
            if(!multiGesture[0]&&e.getPointerCount()==1)gestures.onTouchEvent(e);
            return true;
        });
    }
    boolean pendingCrop(){return activeModel!=null&&"cropped_furniture_pending".equals(activeModel.optString("kind"));}
    boolean calibratedFurniture(){return activeModel!=null&&"calibrated_furniture".equals(activeModel.optString("kind"));}
    boolean croppedFurniture(){return pendingCrop()||calibratedFurniture();}
    void startScan(){if(session==null)startActivity(new android.content.Intent(this,ScanActivity.class));}
    void openModelScene(){if(session!=null)return;if(croppedFurniture())startActivity(new android.content.Intent(this,SceneViewerActivity.class).putExtra("scene_id",activeModel.optString("source_scene_id")).putExtra("edit_crop_id",activeModelId));else SceneViewerActivity.open(this,null);}
    void enterRoom(){
        if(pendingCrop()){
            if(!dimensionsConfirmed){showDimensions(()->enterRoom());return;}
            prepareCropForRoom();return;
        }
        if(!dimensionsConfirmed){showDimensions(()->enterRoom());return;}
        if(!getPreferences(0).getBoolean("arNotice",false)){new AlertDialog.Builder(this).setTitle("放进房间").setMessage("需要相机权限和 Google Play AR 服务，使用相机与传感器进行空间定位。家具照片和模型保存在本机。").setPositiveButton("继续",(d,w)->{getPreferences(0).edit().putBoolean("arNotice",true).apply();startAR();}).setNeutralButton("服务隐私说明",(d,w)->startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://developers.google.com/ar/develop/privacy-requirements")))).setNegativeButton("取消",null).show();}else startAR();
    }
    void prepareCropForRoom(){
        if(preparingRoom||session!=null)return;preparingRoom=true;
        final String source=activeModelId,mode=sizeMode;final float depth=requestedDepth,width=requestedWidth,height=requestedHeight;
        ProgressDialog progress=ProgressDialog.show(this,"准备放进房间","按已确认尺寸整理接地位置…",true,false);
        new Thread(()->{try{String id=GroundCalibration.prepareForRoom(this,source,depth,width,height,mode);runOnUiThread(()->{progress.dismiss();preparingRoom=false;if(!isFinishing()&&source.equals(activeModelId))selectModel(id,true);});}
            catch(Exception error){android.util.Log.e("ChairDemo","Prepare furniture for AR",error);runOnUiThread(()->{progress.dismiss();preparingRoom=false;new AlertDialog.Builder(this).setTitle("暂时无法准备家具").setMessage(error.getMessage()).setPositiveButton("知道了",null).show();});}},"PrepareFurnitureForRoom").start();
    }
    void showRoomModels(){if(session!=null)FurnitureLibrary.show(this,true);}
    void addRoomModel(String id,boolean replace){if(session==null)return;final RoomScene.Furniture target=renderer.room.selected;ProgressDialog progress=ProgressDialog.show(this,"加载家具","正在准备模型…",true,false);new Thread(()->{try{RoomAsset cached=renderer.room.cache.get(id);final RoomAsset asset=cached==null||replace?new RoomAsset(this,id):cached;if(!asset.confirmed)throw new IOException("请先在模型预览中确认实际尺寸");runOnUiThread(()->{progress.dismiss();if(session==null)return;groundPanel.close();surface.queueEvent(()->{if(replace&&renderer.room.selected==target)renderer.room.replace(asset);else if(!replace)renderer.room.queue(asset);else message("校准副本已保存，可从模型列表添加");});});}catch(Exception e){runOnUiThread(()->{progress.dismiss();message("加载失败："+e.getMessage());});}},"LoadRoomFurniture").start();}
    void showSceneObjects(){
        surface.queueEvent(()->{
            RoomScene room=renderer.room;
            room.sync();
            ArrayList<RoomScene.Furniture> all=new ArrayList<>(room.items);
            if(all.isEmpty()){message("请先点击地面放置家具");return;}
            String[] choices=new String[all.size()+3];
            System.arraycopy(groupPositionLabels(room),0,choices,0,3);
            for(int i=0;i<all.size();i++){
                RoomScene.Furniture item=all.get(i);
                choices[i+3]=(i+1)+". "+item.asset.metadata.optString("name","家具")+(item==room.selected?" · 已选中":"");
            }
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("房间中的家具").setItems(choices,(d,index)->{
                groundPanel.close();
                surface.queueEvent(()->{
                    if(index<3)applyGroupPositionAction(room,index);
                    else if(room.items.contains(all.get(index-3))){
                        room.pending=null;
                        room.select(all.get(index-3));
                    }
                });
            }).setNegativeButton("关闭",null).show());
        });
    }
    private String[] groupPositionLabels(RoomScene room){
        return new String[]{"整组移近 20 厘米","整组移远 20 厘米",room.isChoosingGroupPosition()?"取消重选落点":"重选整组落点"};
    }
    private void applyGroupPositionAction(RoomScene room,int index){
        if(index==2)room.toggleGroupPosition();
        else room.adjustGroupDistance(index==0?.20f:-.20f);
    }
    void showGroupPosition(){
        surface.queueEvent(()->{
            RoomScene room=renderer.room;
            if(room.size()==0||room.pending!=null)return;
            String[] choices=groupPositionLabels(room);
            runOnUiThread(()->{
                if(!roomMode)return;
                groundPanel.close();
                new AlertDialog.Builder(this).setTitle("移动整组家具")
                        .setItems(choices,(d,index)->surface.queueEvent(()->applyGroupPositionAction(room,index)))
                        .setNegativeButton("取消",null).show();
            });
        });
    }
    void resetRoom(){if(session!=null&&renderer.room.size()>0)new AlertDialog.Builder(this).setTitle("重置当前摆放？").setMessage("清空房间中的摆放，已保存的家具模型会保留。").setPositiveButton("重置",(d,w)->surface.queueEvent(()->renderer.reset())).setNegativeButton("取消",null).show();else surface.queueEvent(()->renderer.reset());}
    protected boolean initializeViewer(Bundle state){return false;}
    protected InputStream rendererInput()throws Exception{return ModelStore.openPacked(this,activeModelId);}
    protected void previewCamera(float[] view,float[] projection,int width,int height,float yaw,float pitch,float distance){
        float ex=(float)(distance*Math.sin(yaw)*Math.cos(pitch)),ey=requestedHeight*.5f+(float)(distance*Math.sin(pitch)),ez=(float)(distance*Math.cos(yaw)*Math.cos(pitch));
        Matrix.setLookAtM(view,0,ex,ey,ez,0,requestedHeight*.5f,0,0,1,0);Matrix.perspectiveM(projection,0,42,(float)width/height,.03f,50);
    }
    android.content.SharedPreferences sizePreferences(){return ModelStore.BUILTIN.equals(activeModelId)?getPreferences(0):getSharedPreferences("model-size-"+activeModelId,0);}
    void startGeneration(){
        if(session!=null){message("请先返回模型预览，再生成家具");return;}
        FurnitureLibrary.generate(this);
    }
    void showModels(){if(session==null)FurnitureLibrary.show(this,false);}
    void prepareLayout(String id){
        ProgressDialog progress=ProgressDialog.show(this,"读取房间布局","检查家具模型和摆放记录…",true,false);
        new Thread(()->{try{org.json.JSONObject layout=new RoomLayoutStore(getFilesDir()).read(id);org.json.JSONArray entries=layout.getJSONArray("items");HashMap<String,RoomAsset> assets=new HashMap<>();ArrayList<RoomScene.Furniture> furniture=new ArrayList<>();int total=0;
            for(int i=0;i<entries.length();i++){org.json.JSONObject j=entries.getJSONObject(i);String modelId=j.getString("model_id");RoomAsset asset=assets.get(modelId);if(asset==null){if(LibraryStore.modelDeleted(this,modelId))throw new IOException("布局中的模型已删除");asset=new RoomAsset(this,modelId);asset.confirmed=true;assets.put(modelId,asset);}total+=asset.count;if(total>500000)throw new IOException("布局模型较多，超过当前50万高斯显示上限");RoomScene.Furniture f=new RoomScene.Furniture(asset);f.width=(float)j.getDouble("width");f.depth=(float)j.getDouble("depth");f.height=(float)j.getDouble("height");f.contact=(float)j.getDouble("contact");f.yaw=(float)j.getDouble("yaw");f.sizeMode=j.getString("size_mode");f.locked=j.optBoolean("locked",true);f.testPose=new Pose(RoomScene.vector(j.getJSONArray("translation")),RoomScene.vector(j.getJSONArray("rotation")));furniture.add(f);}
            runOnUiThread(()->{progress.dismiss();if(isFinishing())return;if(session!=null){surface.queueEvent(()->renderer.room.queueLayout(furniture));}else{preparedLayout=furniture;RoomScene.Furniture f=furniture.get(0);activeModelId=f.asset.id;activeModel=f.asset.metadata;requestedWidth=f.width;requestedDepth=f.depth;requestedHeight=f.height;dimensionsConfirmed=true;enterRoom();}});
        }catch(Exception e){runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("无法恢复布局").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();});}},"LoadRoomLayout").start();
    }
    void selectModel(String id){selectModel(id,roomMode);}
    void selectModel(String id,boolean enterAfterSelection){
        if(session!=null){addRoomModel(id,true);return;}
        try{ModelStore.activate(this,id);getIntent().putExtra("model_id",id).putExtra("room_mode",enterAfterSelection);recreate();}catch(Exception error){message("模型未能打开："+error.getMessage());}
    }
    void loadDimensions(){
        // Legacy installs contain the original chair. Preserve its measured size,
        // but require fresh confirmation when an APK brings a different object.
        String legacyAsset="e70990bccee261c0ac576dff5ab6ce691610016f766cde159378446765d52915";
        String assetId=legacyAsset;
        float defaultHeight=1;
        try{
        org.json.JSONObject d=ModelStore.dimensions(this,activeModelId);
        nominalWidth=(float)d.getDouble("width_m");nominalDepth=(float)d.getDouble("depth_m");nominalHeight=(float)d.getDouble("height_m");
        assetId=d.optString("asset_id",legacyAsset);
        defaultHeight=(float)d.optDouble("default_height_m",nominalHeight);
    }catch(Exception e){android.util.Log.e("ChairDemo","Dimension metadata",e);}
        if(!assetId.equals(sizePreferences().getString("assetId",legacyAsset))){
            sizePreferences().edit().remove("width").remove("depth").remove("height").remove("sizeMode").putBoolean("dimensionsConfirmed",false).apply();
        }
        sizePreferences().edit().putString("assetId",assetId).apply();
        requestedWidth=sizePreferences().getFloat("width",nominalWidth*defaultHeight/nominalHeight);requestedDepth=sizePreferences().getFloat("depth",nominalDepth*defaultHeight/nominalHeight);requestedHeight=sizePreferences().getFloat("height",defaultHeight);dimensionsConfirmed=sizePreferences().getBoolean("dimensionsConfirmed",false);refreshDimensionSummary();
        sizeMode=sizePreferences().getString("sizeMode","height");
        if(sizePreferences().getInt("calibrationVersion",0)<3){requestedWidth=nominalWidth*requestedHeight/nominalHeight;requestedDepth=nominalDepth*requestedHeight/nominalHeight;dimensionsConfirmed=false;sizeMode="height";}
        if(calibratedFurniture()&&!sizePreferences().getBoolean("calibratedMetadataApplied",false)){requestedWidth=nominalWidth;requestedDepth=nominalDepth;requestedHeight=nominalHeight;dimensionsConfirmed=true;sizeMode="height";sizePreferences().edit().putFloat("width",requestedWidth).putFloat("depth",requestedDepth).putFloat("height",requestedHeight).putBoolean("dimensionsConfirmed",true).putString("sizeMode",sizeMode).putInt("calibrationVersion",4).putBoolean("calibratedMetadataApplied",true).apply();}
        contactOffsetM=calibratedFurniture()?sizePreferences().getFloat("contactOffsetM",0):0;refreshDimensionSummary();
    }
    void refreshDimensionSummary(){dimensionSummary.setText((pendingCrop()?"裁剪范围估计 · ":calibratedFurniture()&&sizeMode.equals("height")?"按实测高度校准 · 长宽估计\n":"")+String.format(Locale.CHINA,"长 %.1f × 宽 %.1f × 高 %.1f cm%s",requestedDepth*100,requestedWidth*100,requestedHeight*100,dimensionsConfirmed?"":" · 请确认实测尺寸"));}
    void applyContactOffset(float metres){if(!calibratedFurniture())throw new IllegalStateException("请先完成接地校准");if(renderer.placementLocked)throw new IllegalStateException("请先解锁家具");if(!Float.isFinite(metres)||Math.abs(metres)>.05001f)throw new IllegalArgumentException("接地微调范围为正负 5 厘米");contactOffsetM=metres;}
    void showContactAdjustment(){
        if(renderer.placementLocked){message("请先解锁家具，再微调接地");return;}
        final float original=contactOffsetM;LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),dp(8),dp(20),0);TextView text=new TextView(this);form.addView(text);SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(Math.round(original*1000)+50);form.addView(slider);Runnable label=()->text.setText(String.format(Locale.CHINA,"上下微调：%+.1f cm\n家具悬浮时向下，陷入地面时向上。调整后重新锁定。",contactOffsetM*100));label.run();slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}public void onProgressChanged(SeekBar v,int p,boolean user){if(user)try{applyContactOffset((p-50)*.001f);label.run();}catch(Exception e){message(e.getMessage());}}});
        final boolean[] saved={false};AlertDialog dialog=new AlertDialog.Builder(this).setTitle("接地微调").setView(form).setNegativeButton("取消",null).setNeutralButton("归零",(d,w)->{contactOffsetM=0;sizePreferences().edit().putFloat("contactOffsetM",0).apply();saved[0]=true;}).setPositiveButton("保存",(d,w)->{sizePreferences().edit().putFloat("contactOffsetM",contactOffsetM).apply();saved[0]=true;}).create();dialog.setOnDismissListener(d->{if(!saved[0])contactOffsetM=original;});dialog.show();
    }
    static float centimetres(String value){float result=Float.parseFloat(value.trim().replace(',','.'))/100f;if(!Float.isFinite(result)||result<.05f||result>10f)throw new IllegalArgumentException("请输入 5～1000 cm 的尺寸");return result;}
    void applyDimensions(float length,float width,float height,boolean keepRatio){
        if(renderer.placementLocked)throw new IllegalStateException("请先解锁椅子");
        if(!Float.isFinite(length)||!Float.isFinite(width)||!Float.isFinite(height)||length<.05f||width<.05f||height<.05f||length>10||width>10||height>10)throw new IllegalArgumentException("尺寸超出范围");
        if(keepRatio){width=nominalWidth*height/nominalHeight;length=nominalDepth*height/nominalHeight;}
        requestedDepth=length;requestedWidth=width;requestedHeight=height;dimensionsConfirmed=true;sizeMode=keepRatio?"height":"axes";
        sizePreferences().edit().putFloat("depth",length).putFloat("width",width).putFloat("height",height).putBoolean("dimensionsConfirmed",true).putString("sizeMode",sizeMode).putInt("calibrationVersion",3).apply();refreshDimensionSummary();
        surface.queueEvent(()->{if(session==null)renderer.distance=2.1f*Math.max(requestedHeight,Math.max(requestedWidth,requestedDepth));});
    }
    void showDimensions(Runnable after){
        if(renderer.placementLocked&&session!=null){message("椅子已锁定，请先点解锁再修改尺寸");return;}
        LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),dp(8),dp(20),0);
        TextView note=new TextView(this);note.setText("单位：厘米。默认按实测高度保持家具形状；取消勾选可按三项尺寸调整形状。长＝前后深度，宽＝左右宽度。请用尺寸框和实物尺对照。");form.addView(note);
        EditText[] fields=new EditText[3];String[] labels={"长（前后）cm","宽（左右）cm","总高 cm"};float[] values={requestedDepth,requestedWidth,requestedHeight};
        for(int i=0;i<3;i++){TextView label=new TextView(this);label.setText(labels[i]);form.addView(label);fields[i]=new EditText(this);fields[i].setSingleLine(true);fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);fields[i].setText(String.format(Locale.US,"%.1f",values[i]*100));form.addView(fields[i]);}
        CheckBox ratio=new CheckBox(this);ratio.setText("保持原比例：按高度校准（推荐）");ratio.setOnCheckedChangeListener((button,checked)->{fields[0].setEnabled(!checked);fields[1].setEnabled(!checked);});ratio.setChecked(!sizeMode.equals("axes"));form.addView(ratio);
        CheckBox fit=new CheckBox(this);fit.setText("综合三项尺寸等比拟合（不拉伸）");form.addView(fit);
        ratio.setOnCheckedChangeListener((button,checked)->{fields[0].setEnabled(!checked);fields[1].setEnabled(!checked);if(checked)fit.setChecked(false);});fit.setOnCheckedChangeListener((button,checked)->{if(checked)ratio.setChecked(false);});fit.setChecked(sizeMode.equals("fit"));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("设置家具实际尺寸").setView(scroll).setNegativeButton("取消",null).setPositiveButton("应用尺寸",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{float h=centimetres(fields[2].getText().toString());float l=ratio.isChecked()?requestedDepth:centimetres(fields[0].getText().toString());float w=ratio.isChecked()?requestedWidth:centimetres(fields[1].getText().toString());
            Runnable apply=()->{if(fit.isChecked()){float s=DimensionFit.scale(nominalWidth,nominalDepth,nominalHeight,w,l,h);applyDimensions(nominalDepth*s,nominalWidth*s,nominalHeight*s,false);sizeMode="fit";sizePreferences().edit().putString("sizeMode","fit").apply();}else applyDimensions(l,w,h,ratio.isChecked());dialog.dismiss();if(after!=null)after.run();};
            float s=DimensionFit.scale(nominalWidth,nominalDepth,nominalHeight,w,l,h);float residual=DimensionFit.residual(nominalWidth,nominalDepth,nominalHeight,w,l,h,s);
            if(fit.isChecked()&&residual>.05)new AlertDialog.Builder(this).setTitle("尺寸比例需核对").setMessage("三项不能同时等比匹配，最大相对偏差约 "+Math.round(residual*100)+"%。建议检查测量和长宽方向；继续会保持形状按综合尺度放置。").setPositiveButton("仍按综合比例",(d,which)->apply.run()).setNegativeButton("返回检查",null).show();else apply.run();
        }catch(Exception e){fields[2].setError("请检查三项数值：每项 5～1000 cm");}}));dialog.show();
    }
    void updateLockLabel(){runOnUiThread(()->{if(ui!=null)ui.refresh();});}
    void placementFeedback(){runOnUiThread(()->surface.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY));}
    private void renderStatus(String text){runOnUiThread(()->{if(ui!=null)ui.showStatus(text);else if(status!=null)status.setText(text);});}
    void message(String text){statusHoldUntil=android.os.SystemClock.uptimeMillis()+1800;renderStatus(text);}
    void slowMessage(String text){
        long now=android.os.SystemClock.uptimeMillis();
        if(now>=statusHoldUntil&&now-lastStatus>800){lastStatus=now;renderStatus(text);}
    }
    void startAR(){
        if(pendingCrop()){message("当前请先核对提取结果，完成落地与尺寸校准后再接入 AR");return;}
        if(groundPanel!=null)groundPanel.close();
        if(paused)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){pendingAR=true;requestPermissions(new String[]{Manifest.permission.CAMERA},7);return;}
        Session created=null;
        try{
            ArCoreApk.Availability availability=ArCoreApk.getInstance().checkAvailability(this);
            if(availability.isTransient()){message("正在检查 AR 服务，请稍后再点一次");return;}
            if(availability==ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE){message("这台设备暂不支持 AR，仍可查看椅子模型");return;}
            if(!availability.isSupported()){message("暂时无法确认 AR 支持，请检查 AR 服务；普通预览仍可使用");return;}
            if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installRequested=true;pendingAR=true;message("请先完成系统提示的 AR 服务安装");return;}
            renderer.nextRoomAsset=new RoomAsset(this,activeModelId);created=new Session(this);Config config=new Config(created);config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            depthSupported=created.isDepthModeSupported(Config.DepthMode.AUTOMATIC);if(depthSupported)config.setDepthMode(Config.DepthMode.AUTOMATIC);created.configure(config);
            android.util.Log.i("ChairDemo","DEPTH_SUPPORTED="+depthSupported);
            surface.onPause();created.resume();session=created;surface.onResume();
            final ArrayList<RoomScene.Furniture> layout=preparedLayout;preparedLayout=null;surface.queueEvent(()->{renderer.reset();if(layout!=null)renderer.room.queueLayout(layout);});if(ui!=null)ui.setRoom(true);message(layout==null?"缓慢移动手机识别地面，再点击放置":"缓慢移动识别地面，再点击指定布局参考位置");pendingAR=false;
        }catch(Exception e){if(created!=null&&session==null)created.close();surface.onResume();message("AR 暂时不可用："+friendly(e)+"。可以继续普通预览");pendingAR=false;}
    }
    String friendly(Exception e){
        if(e instanceof UnavailableDeviceNotCompatibleException)return "设备或系统未获支持";
        if(e instanceof UnavailableArcoreNotInstalledException)return "缺少 AR 服务";
        if(e instanceof UnavailableApkTooOldException)return "请更新 AR 服务";
        if(e instanceof UnavailableSdkTooOldException)return "AR 组件版本需更新";
        if(e instanceof UnavailableUserDeclinedInstallationException)return "尚未安装 AR 服务";
        if(e instanceof CameraNotAvailableException)return "摄像头被占用";
        return e.getClass().getSimpleName();
    }
    void leaveAR(){
        if(groundPanel!=null)groundPanel.close();showDepthMap=false;if(ui!=null)ui.depth.setChecked(false);surface.onPause();Session old=session;renderer.room.preview(renderer);renderer.room.clear();renderer.room.cache.clear();session=null;renderer.reset();
        if(old!=null){old.pause();old.close();}
        try{ModelStore.activate(this,activeModelId);getIntent().putExtra("model_id",activeModelId).putExtra("room_mode",false);}catch(Exception ignored){}surface.onResume();if(ui!=null)ui.setRoom(false);updateLockLabel();
    }
    void requestLeaveRoom(){if(session!=null&&renderer.room.size()>0)new AlertDialog.Builder(this).setTitle("返回前保存房间布局？").setMessage("未保存的摆放会在离开房间后清空，家具模型会保留。").setPositiveButton("保存并返回",(d,w)->RoomLayouts.save(this,()->leaveAR())).setNeutralButton("直接返回",(d,w)->leaveAR()).setNegativeButton("继续摆放",null).show();else leaveAR();}
    @Override public void onBackPressed(){if(roomMode){requestLeaveRoom();return;}super.onBackPressed();}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] result){super.onRequestPermissionsResult(code,p,result);if(code==7){pendingAR=false;if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED)startAR();else message("未开启相机权限，仍可普通预览");}}
    @Override protected void onResume(){super.onResume();paused=false;if(surface!=null){if(session!=null)try{session.resume();}catch(Exception e){message("相机无法恢复，请返回模型预览后重试");}surface.onResume();}if(pendingAR){pendingAR=false;getIntent().removeExtra("room_mode");enterRoom();}}
    @Override protected void onPause(){paused=true;if(surface!=null)surface.onPause();if(session!=null)session.pause();super.onPause();}
    @Override protected void onDestroy(){if(session!=null){renderer.reset();session.close();session=null;}super.onDestroy();}

    class ChairRenderer implements GLSurfaceView.Renderer {
        final RoomScene room=new RoomScene(MainActivity.this);RoomAsset nextRoomAsset;
        static final int FLOATS=37;
        float[] rows,depth,rawInverse=new float[9];Integer[] order;FloatBuffer upload;
        int count,program,background,instanceBuffer,quadBuffer,cameraTex,depthTex,lineProgram,depthViewProgram;
        ByteBuffer depthPixels;
        short[] fullDepth,rawDepth;
        byte[] rawConfidence,luma;
        final DepthStabilizer stabilizer=new DepthStabilizer();
        boolean depthReady=false;
        int depthFrames=0;
        float[] depthUvTransform=new float[9];
        volatile boolean placementLocked=false;
        volatile long renderedFrames=0;
        int width=1,height=1;float yaw=0,pitch=.08f,distance=2.1f,objectScale=1;
        float[] projection=new float[16],view=new float[16],model=new float[16],vm=new float[16],inverse=new float[16],eye=new float[4];
        FloatBuffer cameraQuad=buffer(new float[]{-1,-1,1,-1,-1,1,1,1}),cameraUV=buffer(new float[8]);
        float[] tap;volatile Anchor anchor;String fatal;
        FloatBuffer buffer(float[] a){FloatBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(a).position(0);return b;}
        String asset(String name)throws IOException{try(InputStream in=getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");}}
        void reset(){RoomAsset selectedAsset=room.selected==null?nextRoomAsset:room.selected.asset;room.clear();yaw=0;pitch=.08f;distance=2.1f*Math.max(requestedHeight,Math.max(requestedWidth,requestedDepth));objectScale=1;tap=null;placementLocked=false;depthFrames=0;stabilizer.reset();anchor=null;if(session!=null&&selectedAsset!=null)room.start(selectedAsset);if(lockButton!=null)updateLockLabel();}
        void requestPlacement(float x,float y){if(session!=null||!placementLocked)tap=new float[]{x,y};}
        void scale(float factor){if(placementLocked)return;if(session==null){float size=Math.max(requestedHeight,Math.max(requestedWidth,requestedDepth));distance=Math.max(size*.7f,Math.min(size*4.5f,distance/factor));}}
        void drag(float dx,float dy){if(placementLocked)return;yaw-=dx*.006f;if(session==null)pitch=Math.max(-.25f,Math.min(.8f,pitch+dy*.004f));}
        int shader(int type,String source){int shader=GLES30.glCreateShader(type);GLES30.glShaderSource(shader,source);GLES30.glCompileShader(shader);int[] ok=new int[1];GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException(GLES30.glGetShaderInfoLog(shader));return shader;}
        int program(String vert,String frag)throws IOException{int p=GLES30.glCreateProgram();GLES30.glAttachShader(p,shader(GLES30.GL_VERTEX_SHADER,asset(vert)));GLES30.glAttachShader(p,shader(GLES30.GL_FRAGMENT_SHADER,asset(frag)));GLES30.glLinkProgram(p);int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new RuntimeException(GLES30.glGetProgramInfoLog(p));return p;}
        @Override public void onSurfaceCreated(GL10 unused,EGLConfig config){try{
            room.contextCreated();
            program=program("splat.vert","splat.frag");background=0;
            try(InputStream input=rendererInput()){PackedModel packed=PackedModel.read(input);count=packed.count;System.arraycopy(packed.inverse,0,rawInverse,0,9);rows=packed.rows;android.util.Log.i("ChairDemo","MODEL_LOADED id="+activeModelId+" gaussians="+count);}
            depth=new float[count];order=new Integer[count];for(int i=0;i<count;i++)order[i]=i;upload=ByteBuffer.allocateDirect(rows.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            int[] ids=new int[2];GLES30.glGenBuffers(2,ids,0);quadBuffer=ids[0];instanceBuffer=ids[1];GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,quadBuffer);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,32,buffer(new float[]{-1,-1,1,-1,-1,1,1,1}),GLES30.GL_STATIC_DRAW);
            GLES30.glGenTextures(1,ids,0);cameraTex=ids[0];GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTex);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glGenTextures(1,ids,0);depthTex=ids[0];GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RG16UI,1,1,0,GLES30.GL_RG_INTEGER,GLES30.GL_UNSIGNED_SHORT,ByteBuffer.allocateDirect(4));
            lineProgram=program("line.vert","line.frag");depthViewProgram=program("depth-view.vert","depth-view.frag");stabilizer.reset();
        }catch(Exception e){fatal=e.toString();android.util.Log.e("ChairDemo","Renderer init",e);message("模型加载失败，请把提示告诉我："+e.getMessage());}}
        @Override public void onSurfaceChanged(GL10 unused,int w,int h){width=w;height=h;GLES30.glViewport(0,0,w,h);}
        void cameraBackground(Frame frame){
            if(frame.getTimestamp()==0)return;
            if(background==0)try{background=program("camera.vert","camera.frag");}catch(Exception e){throw new RuntimeException("Camera shader unavailable",e);}
            cameraQuad.position(0);cameraUV.position(0);frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,cameraQuad,Coordinates2d.TEXTURE_NORMALIZED,cameraUV);
            GLES30.glDisable(GLES30.GL_BLEND);GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glUseProgram(background);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTex);GLES30.glUniform1i(GLES30.glGetUniformLocation(background,"cameraTexture"),0);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
            int pos=GLES30.glGetAttribLocation(background,"position"),uv=GLES30.glGetAttribLocation(background,"uv");cameraQuad.position(0);cameraUV.position(0);GLES30.glEnableVertexAttribArray(pos);GLES30.glEnableVertexAttribArray(uv);GLES30.glVertexAttribDivisor(pos,0);GLES30.glVertexAttribDivisor(uv,0);GLES30.glVertexAttribPointer(pos,2,GLES30.GL_FLOAT,false,0,cameraQuad);GLES30.glVertexAttribPointer(uv,2,GLES30.GL_FLOAT,false,0,cameraUV);GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4);GLES30.glDisableVertexAttribArray(pos);GLES30.glDisableVertexAttribArray(uv);
        }
        void updateDepth(Frame frame){
            depthReady=false;
            if(!depthSupported)return;
            int w=stabilizer.width,h=stabilizer.height;short[] current=null,raw=null;byte[] confidence=null,guidance=null;
            float u=cameraUV.get(0),v=cameraUV.get(1);
            depthUvTransform=new float[]{cameraUV.get(2)-u,cameraUV.get(3)-v,0,cameraUV.get(4)-u,cameraUV.get(5)-v,0,u,v,1};
            try(Image image=frame.acquireDepthImage16Bits()){
                w=image.getWidth();h=image.getHeight();if(fullDepth==null||fullDepth.length!=w*h){fullDepth=new short[w*h];rawDepth=new short[w*h];rawConfidence=new byte[w*h];luma=new byte[w*h];}
                readDepth(image,fullDepth);current=fullDepth;
            }catch(NotYetAvailableException e){/* Reproject reliable history for at most 120 ms. */}
            if(!EXPERIMENTAL_DEPTH){
                if(current==null)return;
                int n=w*h;if(depthPixels.capacity()<n*4)depthPixels=ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder());depthPixels.clear();
                for(short z:current){depthPixels.putShort(z);depthPixels.putShort((short)255);}depthPixels.flip();
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,1);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RG16UI,w,h,0,GLES30.GL_RG_INTEGER,GLES30.GL_UNSIGNED_SHORT,depthPixels);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);depthReady=true;
                if(++depthFrames==1)android.util.Log.i("ChairDemo","DEPTH_FRAME="+w+"x"+h+" BASELINE");return;
            }
            if(current!=null){
                try(Image image=frame.acquireRawDepthImage16Bits();Image quality=frame.acquireRawDepthConfidenceImage()){
                    if(image.getWidth()==w&&image.getHeight()==h&&quality.getWidth()==w&&quality.getHeight()==h){readDepth(image,rawDepth);Image.Plane plane=quality.getPlanes()[0];ByteBuffer b=plane.getBuffer();int base=b.position();for(int y=0;y<h;y++)for(int x=0;x<w;x++)rawConfidence[y*w+x]=b.get(base+y*plane.getRowStride()+x*plane.getPixelStride());raw=rawDepth;confidence=rawConfidence;}
                }catch(NotYetAvailableException e){/* Raw evidence is optional and sparse. */}
                try(Image image=frame.acquireCameraImage()){
                    float[] coords=new float[6];frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED,new float[]{0,0,1,0,0,1},Coordinates2d.IMAGE_PIXELS,coords);
                    Image.Plane plane=image.getPlanes()[0];ByteBuffer b=plane.getBuffer();int base=b.position();
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++){float a=(x+.5f)/w,c=(y+.5f)/h;int xx=Math.max(0,Math.min(image.getWidth()-1,(int)(coords[0]+a*(coords[2]-coords[0])+c*(coords[4]-coords[0])))),yy=Math.max(0,Math.min(image.getHeight()-1,(int)(coords[1]+a*(coords[3]-coords[1])+c*(coords[5]-coords[1]))));luma[y*w+x]=b.get(base+yy*plane.getRowStride()+xx*plane.getPixelStride());}guidance=luma;
                }catch(NotYetAvailableException e){/* Depth-edge filtering still operates. */}
            }
            if(stabilizer.process(w,h,current,raw,confidence,guidance,frame.getTimestamp(),view,projection,depthUvTransform)){
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,1);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RG16UI,w,h,0,GLES30.GL_RG_INTEGER,GLES30.GL_UNSIGNED_SHORT,stabilizer.packed);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                depthReady=true;
                if(++depthFrames==1)android.util.Log.i("ChairDemo","DEPTH_FRAME="+w+"x"+h+" STABILIZED");
            }
        }
        void readDepth(Image image,short[] target){int bytes=image.getWidth()*image.getHeight()*2;if(depthPixels==null||depthPixels.capacity()!=bytes)depthPixels=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());Image.Plane plane=image.getPlanes()[0];DepthUpload.copy(plane.getBuffer(),image.getWidth(),image.getHeight(),plane.getRowStride(),plane.getPixelStride(),depthPixels);depthPixels.asShortBuffer().get(target);}
        String depthStatus(){return !depthSupported?"设备未提供深度，遮挡不可用":depthReady?(stabilizer.reused?"深度短时稳定中":"实物遮挡已开启"):"等待场景深度，请缓慢移动手机";}
        @Override public void onDrawFrame(GL10 unused){
            GLES30.glClearColor(27/255f,37/255f,32/255f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);if(fatal!=null)return;
            Matrix.setIdentityM(model,0);depthReady=false;
            Session active=session;
            if(active!=null){try{
                active.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(),width,height);active.setCameraTextureName(cameraTex);Frame frame=active.update();cameraBackground(frame);Camera camera=frame.getCamera();
                if(camera.getTrackingState()!=TrackingState.TRACKING){room.updateCameraPose(null);stabilizer.reset();slowMessage("缓慢移动手机，让相机看清地面和周围纹理");return;}
                room.updateCameraPose(camera.getPose());
                camera.getProjectionMatrix(projection,0,.03f,30);camera.getViewMatrix(view,0);
                updateDepth(frame);
                if(showDepthMap&&depthReady){drawDepthMap();slowMessage("暖色近、冷色远（0.2～5 米） · 黑色暂无数据");return;}
                if(tap!=null){float[] point=tap;tap=null;showGroundGuide=false;runOnUiThread(()->groundPanel.close());room.tap(frame,point[0],point[1],this);}
                room.draw(this);slowMessage(room.placementHint(placementLocked)+" · "+depthStatus());renderedFrames++;return;
            }catch(Exception e){room.updateCameraPose(null);android.util.Log.e("ChairDemo","AR frame",e);slowMessage("AR 暂停，请点击返回模型预览后重试");return;}}
            else{
                previewCamera(view,projection,width,height,yaw,pitch,distance);
            }
            Matrix.translateM(model,0,0,contactOffsetM,0);
            Matrix.scaleM(model,0,requestedWidth/nominalWidth,requestedHeight/nominalHeight,requestedDepth/nominalDepth);
            Matrix.multiplyMM(vm,0,view,0,model,0);Matrix.invertM(inverse,0,vm,0);Matrix.multiplyMV(eye,0,inverse,0,new float[]{0,0,0,1},0);
            drawSplats();
            if(showSizeGuide)drawSizeGuide();
            if(showGroundGuide&&active==null)drawGroundGuide();
            renderedFrames++;
        }
        void drawDepthMap(){GLES30.glUseProgram(depthViewProgram);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glUniform1i(GLES30.glGetUniformLocation(depthViewProgram,"depthTexture"),1);GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(depthViewProgram,"depthUvTransform"),1,false,depthUvTransform,0);GLES30.glUniform2f(GLES30.glGetUniformLocation(depthViewProgram,"viewport"),width,height);GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex);GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);}
        void drawSizeGuide(){
            float x=nominalWidth*.5f,z=nominalDepth*.5f,h=nominalHeight;
            float[][] c={{-x,0,-z},{x,0,-z},{x,0,z},{-x,0,z},{-x,h,-z},{x,h,-z},{x,h,z},{-x,h,z}};int[] edges={0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};
            float[] lines=new float[edges.length*3+72];int j=0;for(int i:edges)for(float v:c[i])lines[j++]=v;
            // The ruler is exactly one world metre, independent of furniture height.
            float ry=nominalHeight/requestedHeight,rx=x+nominalWidth*.15f;
            for(int i=0;i<=10;i++){float yy=i*.1f*ry;lines[j++]=rx;lines[j++]=yy;lines[j++]=z;lines[j++]=rx+nominalWidth*.05f;lines[j++]=yy;lines[j++]=z;}
            lines[j++]=rx;lines[j++]=0;lines[j++]=z;lines[j++]=rx;lines[j++]=ry;lines[j++]=z;
            float[] mvp=new float[16];Matrix.multiplyMM(mvp,0,projection,0,vm,0);GLES30.glUseProgram(lineProgram);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram,"mvp"),1,false,mvp,0);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,buffer(lines));GLES30.glLineWidth(2);GLES30.glDrawArrays(GLES30.GL_LINES,0,lines.length/3);GLES30.glDisableVertexAttribArray(0);
        }
        void drawGroundGuide(){float x=nominalWidth*.6f,z=nominalDepth*.6f,y=groundGuideY;float[] lines={-x,y,-z,x,y,-z,x,y,-z,x,y,z,x,y,z,-x,y,z,-x,y,z,-x,y,-z,-x,y,0,x,y,0,0,y,-z,0,y,z};float[] mvp=new float[16];Matrix.multiplyMM(mvp,0,projection,0,vm,0);GLES30.glUseProgram(lineProgram);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram,"mvp"),1,false,mvp,0);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribDivisor(0,0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,buffer(lines));GLES30.glLineWidth(2);GLES30.glDrawArrays(GLES30.GL_LINES,0,lines.length/3);GLES30.glDisableVertexAttribArray(0);}
        void drawSplats(){
            for(int i=0;i<count;i++){int k=i*FLOATS;depth[i]=vm[2]*rows[k]+vm[6]*rows[k+1]+vm[10]*rows[k+2]+vm[14];}
            Arrays.sort(order,(a,b)->Float.compare(depth[a],depth[b]));upload.clear();for(int i:order)upload.put(rows,i*FLOATS,FLOATS);upload.flip();
            drawBatch(upload,FLOATS,count,false);
        }
        void drawBatch(FloatBuffer data,int stride,int total,boolean multi){
            GLES30.glUseProgram(program);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"viewModel"),1,false,vm,0);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"projection"),1,false,projection,0);GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program,"metricToRawRotation"),1,false,rawInverse,0);GLES30.glUniform3f(GLES30.glGetUniformLocation(program,"eyeLocal"),eye[0],eye[1],eye[2]);GLES30.glUniform2f(GLES30.glGetUniformLocation(program,"viewport"),width,height);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"scenePreview"),MainActivity.this instanceof SceneViewerActivity?1:0);
            float[] ip=new float[16];Matrix.invertM(ip,0,projection,0);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"inverseProjection"),1,false,ip,0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"useDepth"),depthReady?1:0);GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program,"depthUvTransform"),1,false,depthUvTransform,0);GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"depthTexture"),1);GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depthTex);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"smoothEdges"),smoothOcclusionEdges?1:0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"multiScene"),multi?1:0);GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"objectTransforms"),2);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,quadBuffer);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribDivisor(0,0);GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,8,0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,instanceBuffer);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,data.remaining()*4,data,GLES30.GL_DYNAMIC_DRAW);
            for(int loc=1;loc<=12;loc++){int size=loc==4?4:3;int offset=loc<=4?(loc-1)*3:13+(loc-5)*3;GLES30.glEnableVertexAttribArray(loc);GLES30.glVertexAttribDivisor(loc,1);GLES30.glVertexAttribPointer(loc,size,GLES30.GL_FLOAT,false,stride*4,offset*4);}
            if(multi){GLES30.glEnableVertexAttribArray(13);GLES30.glVertexAttribDivisor(13,1);GLES30.glVertexAttribPointer(13,1,GLES30.GL_FLOAT,false,stride*4,37*4);}
            GLES30.glEnable(GLES30.GL_BLEND);GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ONE_MINUS_SRC_ALPHA);GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glDisable(GLES30.GL_CULL_FACE);GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP,0,4,total);
            for(int loc=0;loc<=13;loc++){GLES30.glDisableVertexAttribArray(loc);GLES30.glVertexAttribDivisor(loc,0);}
            int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR){android.util.Log.e("ChairDemo","GL error "+error);slowMessage("显示兼容性错误："+error);}
        }
    }
}
