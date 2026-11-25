package com.furniture.chairdemo;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;

/** In-app QR scanner; camera frames stay on device and are not saved. */
@SuppressWarnings("deprecation")
public class PairScannerActivity extends Activity implements SurfaceHolder.Callback {
    SurfaceView preview;TextView status;Camera camera;boolean resumed,ready,done;volatile boolean decoding;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        root.addView(Ui.toolbar(this,"扫码连接",this::finish));
        status=Ui.text(this,"对准电脑服务页面上的二维码。",14,Ui.MUTED);int pad=Ui.dp(this,16);status.setPadding(pad,0,pad,Ui.dp(this,10));root.addView(status);
        preview=new SurfaceView(this);preview.getHolder().addCallback(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);WindowLayout.safe(root);}
    @Override protected void onResume(){super.onResume();resumed=true;if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.CAMERA},40);else open();}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] grant){super.onRequestPermissionsResult(code,p,grant);if(code==40&&grant.length>0&&grant[0]==PackageManager.PERMISSION_GRANTED)open();else status.setText("未授予相机权限，可以返回手动连接电脑。");}
    @Override public void surfaceCreated(SurfaceHolder holder){ready=true;open();}
    @Override public void surfaceChanged(SurfaceHolder h,int format,int w,int height){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){ready=false;release();}
    void open(){if(!resumed||!ready||done||camera!=null||checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{int back=0;Camera.CameraInfo info=new Camera.CameraInfo();for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){back=i;break;}}
            camera=Camera.open(back);Camera.getCameraInfo(back,info);Camera.Parameters params=camera.getParameters();Camera.Size choice=null;
            for(Camera.Size s:params.getSupportedPreviewSizes())if(s.width<=1280&&(choice==null||s.width*s.height>choice.width*choice.height))choice=s;
            if(choice!=null)params.setPreviewSize(choice.width,choice.height);params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> modes=params.getSupportedFocusModes();if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(params);int degrees=getWindowManager().getDefaultDisplay().getRotation()*90;camera.setDisplayOrientation((info.orientation-degrees+360)%360);camera.setPreviewDisplay(preview.getHolder());
            camera.setPreviewCallback((data,source)->{if(decoding||done||!resumed)return;Camera.Size s=source.getParameters().getPreviewSize();byte[] y=Arrays.copyOf(data,s.width*s.height);decoding=true;worker.execute(()->decode(y,s.width,s.height));});camera.startPreview();
        }catch(Exception error){release();status.setText("相机暂不可用，请返回手动连接或重新进入扫码。");}}
    void decode(byte[] y,int w,int h){try{Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));hints.put(DecodeHintType.TRY_HARDER,true);
        MultiFormatReader reader=new MultiFormatReader();reader.setHints(hints);LuminanceSource source=new PlanarYUVLuminanceSource(y,w,h,0,0,w,h,false);String text;
        try{text=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))).getText();}catch(NotFoundException missing){reader.reset();text=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source.invert()))).getText();}
        final String result=text;PairingCode.parse(result);runOnUiThread(()->{if(done||!resumed)return;done=true;release();setResult(RESULT_OK,new Intent().putExtra("pairing",result));finish();});
    }catch(NotFoundException ignored){}catch(Exception invalid){runOnUiThread(()->status.setText("请扫描家居重建电脑页面上的二维码，其他二维码无法连接。"));}finally{decoding=false;}}
    void release(){if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();}catch(Exception ignored){}camera.release();camera=null;}}
    @Override protected void onPause(){resumed=false;release();super.onPause();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
