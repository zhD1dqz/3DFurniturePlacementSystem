package com.furniture.reconstructionprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.furniture.chairdemo.StudioDialog;
import com.furniture.chairdemo.StudioIcon;
import com.furniture.chairdemo.Ui;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.json.JSONObject;

/** Isolated development probe. No camera, network or shared-storage permission. */
public class MainActivity extends Activity {
    static volatile MainActivity currentActivity;
    static boolean engineBusy=false;
    static native String runNative(String input,String output,int steps);
    final Handler handler=new Handler(Looper.getMainLooper());
    volatile boolean running=false;
    TextView status;
    Button shortTest,longTest,cancel;
    ProgressBar activityIndicator;
    volatile boolean cancelRequested=false;
    File output,preparedDataset;
    boolean newCapture(){return getIntent().getStringExtra("capture_id")!=null;}
    long started;
    String read(File file) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    boolean integrated(){try{Class.forName("com.furniture.chairdemo.ModelStore");return true;}catch(ClassNotFoundException error){return false;}}
    File datasetFolder()throws Exception{
        if(preparedDataset!=null)return preparedDataset;
        if(!integrated()){
            String name=getIntent().getStringExtra("probe_dataset_name");
            if(name!=null&&!name.matches("quality-pose-(control|refined)"))throw new IOException("Invalid diagnostic dataset");
            return new File(getFilesDir(),name==null?"dataset":name);
        }
        String config;try(InputStream input=getAssets().open("training-enabled.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);config=out.toString("UTF-8");}
        String key=new JSONObject(config).getString("fixture_sha256");if(!key.matches("[a-f0-9]{64}"))throw new IOException("拍摄数据编号无效");
        return new File(getFilesDir(),"dataset-"+key.substring(0,16));
    }
    String describe(String text){
        try{
            JSONObject data=new JSONObject(text);String phase=data.optString("phase");String label;
            if(phase.equals("initializing_gpu"))label="准备手机计算引擎…";
            else if(phase.equals("loading_posed_images"))label="载入拍摄数据…";
            else if(phase.equals("dataset_loaded"))label="已载入 "+data.optInt("views")+" 张图像，准备生成…";
            else if(phase.equals("training"))label="正在生成模型："+data.optInt("iteration")+" / "+data.optInt("steps")+" 步";
            else if(phase.equals("exporting"))label="已到保存时间，正在整理当前模型…";
            else if(phase.equals("completed"))label="本地模型已生成\n用时约 "+Math.round(data.optDouble("wall_seconds"))+" 秒\n模型大小约 "+String.format(java.util.Locale.CHINA,"%.2f",data.optDouble("model_bytes")/1048576)+" MB\n"+(data.optBoolean("stopped_for_time_budget")?"达到时间预算，已保存 "+data.optInt("completed_steps")+" / "+data.optInt("requested_steps")+" 步的模型。":"结果已保存在手机，等待检查画质。");
            else if(phase.equals("timed_out"))label="已达到十分钟保护上限，本次计算已停止。原始照片和已有模型已保留。";
            else if(phase.equals("failed"))label="本次验证未完成\n"+data.optString("error");
            else if(phase.equals("cancelled"))label="已取消本次生成，原照片和已有模型已保留。";
            else label="正在处理…";
            return label;
        }catch(Exception ignored){return text;}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        currentActivity=this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean app=integrated();
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        int edge=Ui.dp(this,14);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        root.addView(Ui.toolbar(this,app?"手机本地重建":"手机本地重建验证",this::onBackPressed));Ui.divider(root);
        LinearLayout content=Ui.column(this);content.setPadding(edge,edge,edge,edge);
        LinearLayout progress=Ui.column(this);progress.setPadding(edge,edge,edge,edge);progress.setBackground(Ui.surface(this,Ui.PANEL,18));
        progress.addView(Ui.heading(this,"重建进度",16));Ui.space(progress,8);
        status=Ui.text(this,"等待开始",15,Ui.TEXT);progress.addView(status);
        activityIndicator=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        activityIndicator.setIndeterminate(true);activityIndicator.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        activityIndicator.setVisibility(View.GONE);progress.addView(activityIndicator,new LinearLayout.LayoutParams(-1,Ui.dp(this,12)));
        Ui.space(progress,10);progress.addView(Ui.text(this,app?"请保持本页面打开。处理最长十分钟。":"使用已有椅子拍摄数据在本机生成模型。",13,Ui.MUTED));
        content.addView(progress);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=Ui.column(this);footer.setPadding(edge,0,edge,edge);root.addView(footer);
        shortTest=Ui.button(this,"开始短程验证 · 20 步",false);shortTest.setOnClickListener(v->start(20));if(!app)footer.addView(shortTest,Ui.margin(this,-1,48));
        longTest=Ui.button(this,app?"开始生成":"开始生成测试",true);longTest.setOnClickListener(v->start(1200));footer.addView(longTest,Ui.margin(this,-1,52));
        cancel=Ui.button(this,"取消生成",false);cancel.setEnabled(false);cancel.setVisibility(View.GONE);cancel.setOnClickListener(v->confirmCancel());footer.addView(cancel,Ui.margin(this,-1,52));
        setContentView(root);root.requestApplyInsets();
        int auto=getIntent().getIntExtra("probe_steps",0);if(auto==20||auto==300||auto==1200)handler.postDelayed(()->start(auto),700);
        else if(integrated()&&getIntent().getBooleanExtra("auto_generate",false))handler.postDelayed(()->start(1200),700);
    }
    void unpack() throws Exception {
        File dataset=datasetFolder();dataset.mkdirs();
        if(new File(dataset,"READY").isFile())return;
        String base=dataset.getCanonicalPath()+File.separator;
        try(ZipInputStream archive=new ZipInputStream(getAssets().open("posed-chair-capture.zip"))){
            ZipEntry entry;byte[] buffer=new byte[65536];
            while((entry=archive.getNextEntry())!=null){
                File path=new File(dataset,entry.getName());if(!path.getCanonicalPath().startsWith(base))throw new IOException("Archive path escaped task");
                if(entry.isDirectory()){path.mkdirs();continue;}
                path.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(path)){int n;while((n=archive.read(buffer))!=-1)out.write(buffer,0,n);}
            }
        }
        try(OutputStream flag=new FileOutputStream(new File(dataset,"READY"))){flag.write(1);}
    }
    void start(int steps){
        if(running)return;
        synchronized(MainActivity.class){if(engineBusy){Toast.makeText(this,"已有生成任务正在运行",Toast.LENGTH_SHORT).show();return;}engineBusy=true;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running=true;cancelRequested=false;cancel.setEnabled(true);shortTest.setEnabled(false);longTest.setEnabled(false);started=SystemClock.elapsedRealtime();
        activityIndicator.setVisibility(View.VISIBLE);longTest.setVisibility(View.GONE);cancel.setVisibility(View.VISIBLE);
        output=new File(getFilesDir(),"probe-"+System.currentTimeMillis()+"-"+steps);output.mkdirs();status.setText("准备本地数据…");
        try{
            String encoded=getIntent().getStringExtra("probe_config_b64");
            if(encoded!=null)try(OutputStream config=new FileOutputStream(new File(output,"probe-config.json"))){config.write(android.util.Base64.decode(encoded,android.util.Base64.URL_SAFE));}
            else if(newCapture())try(FileWriter config=new FileWriter(new File(output,"probe-config.json"))){config.write(qualityConfig().toString());}
        }catch(Exception error){android.util.Log.w("FurnitureProbe","Probe configuration",error);}
        try(FileWriter process=new FileWriter(new File(output,"pid.txt"))){process.write(Integer.toString(android.os.Process.myPid()));}catch(IOException ignored){}
        try(FileWriter latest=new FileWriter(new File(getFilesDir(),"latest-output.txt"))){latest.write(output.getName());}catch(IOException ignored){}
        final File expectedOutput=output;
        Thread budgetGuard=new Thread(()->{
            SystemClock.sleep(Math.max(0,540000-(SystemClock.elapsedRealtime()-started)));
            if(running&&expectedOutput.equals(output))try(FileWriter flag=new FileWriter(new File(expectedOutput,"STOP_AND_SAVE"))){flag.write("ten-minute generation budget");}catch(IOException ignored){}
            SystemClock.sleep(Math.max(0,600000-(SystemClock.elapsedRealtime()-started)));
            if(running&&expectedOutput.equals(output)){
                try{String message=new JSONObject().put("phase","timed_out").put("error","Ten-minute generation limit reached; original data and checkpoints retained").toString();for(String name:new String[]{"progress.json","integration-result.json"})try(FileWriter f=new FileWriter(new File(expectedOutput,name))){f.write(message);}}catch(Exception ignored){}
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        },"TenMinuteGenerationGuard");budgetGuard.setDaemon(true);budgetGuard.start();
        new Thread(()->{
            String result,readyModel=null;
            try{
                if(integrated()){
                    Class<?> store=Class.forName("com.furniture.chairdemo.ModelStore");String capture=newCapture()?getIntent().getStringExtra("capture_id"):(String)store.getMethod("referenceCaptureId",android.content.Context.class).invoke(null,this);
                    try(FileWriter file=new FileWriter(new File(expectedOutput,"capture-source.json"))){file.write(new JSONObject().put("capture_id",capture).toString());}
                }
                if(newCapture()){preparedDataset=(File)Class.forName("com.furniture.chairdemo.CaptureDataset").getMethod("prepare",android.content.Context.class,String.class).invoke(null,this,getIntent().getStringExtra("capture_id"));
                    try(FileWriter file=new FileWriter(new File(expectedOutput,"input-report.json"))){file.write(read(new File(preparedDataset,"input-report.json")));}
                }else unpack();if(cancelRequested)throw new IOException("Cancelled by user");System.loadLibrary("furniture_probe");
                result=runNative(datasetFolder().getAbsolutePath(),expectedOutput.getAbsolutePath(),steps);
                if(integrated()){
                    JSONObject nativeResult=new JSONObject(result);
                    if("completed".equals(nativeResult.optString("phase"))&&newCapture()){
                        nativeResult.put("scope","Phone-captured images, ARCore poses and phone-prepared sparse initialization; full scene, no furniture extraction").put("capture_id",getIntent().getStringExtra("capture_id")).put("generation_and_prepare_ms",SystemClock.elapsedRealtime()-started).put("handoff_ready",false);
                        result=nativeResult.toString();try(FileWriter f=new FileWriter(new File(expectedOutput,"progress.json"))){f.write(result);}try(FileWriter f=new FileWriter(new File(expectedOutput,"result.json"))){f.write(result);}try(FileWriter f=new FileWriter(new File(expectedOutput,"integration-result.json"))){f.write(result);}
                    }else if("completed".equals(nativeResult.optString("phase"))){
                        handler.post(()->status.setText("模型已生成，正在提取家具并保存…"));
                        String filename=nativeResult.getString("model");if(!filename.matches("generated_\\d+\\.ply"))throw new IOException("生成结果文件名无效");
                        Class<?> store=Class.forName("com.furniture.chairdemo.ModelStore");
                        readyModel=(String)store.getMethod("importGenerated",android.content.Context.class,File.class,Float.TYPE).invoke(null,this,new File(expectedOutput,filename),getIntent().getFloatExtra("reference_height_m",1));
                        nativeResult.put("model_id",readyModel);nativeResult.put("handoff_ready",true);nativeResult.put("generation_and_import_ms",SystemClock.elapsedRealtime()-started);
                        result=nativeResult.toString();
                        try(FileWriter file=new FileWriter(new File(expectedOutput,"integration-result.json"))){file.write(result);}
                        android.util.Log.i("ChairDemo","GENERATION_READY id="+readyModel+" total_ms="+(SystemClock.elapsedRealtime()-started));
                    }
                    else try(FileWriter file=new FileWriter(new File(expectedOutput,"integration-result.json"))){file.write(result);}
                }
            }catch(Throwable error){
                readyModel=null;Throwable reason=error.getCause()!=null?error.getCause():error;
                try{result=new JSONObject().put("phase",cancelRequested?"cancelled":"failed").put("error",reason.getMessage()!=null?reason.getMessage():reason.toString()).toString();}catch(Exception ignored){result="本次生成未完成";}
                try(FileWriter file=new FileWriter(new File(expectedOutput,"integration-result.json"))){file.write(result);}catch(IOException ignored){}
                android.util.Log.e("FurnitureProbe","Java/native call",error);
            }
            final String text=result,modelId=readyModel;running=false;synchronized(MainActivity.class){engineBusy=false;}
            handler.post(()->{
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText(describe(text));shortTest.setEnabled(true);longTest.setEnabled(true);cancel.setEnabled(false);
                activityIndicator.setVisibility(View.GONE);longTest.setVisibility(View.VISIBLE);cancel.setVisibility(View.GONE);
                if(modelId!=null){
                    Intent preview=new Intent().setClassName(getPackageName(),"com.furniture.chairdemo.MainActivity").putExtra("model_id",modelId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(preview);finish();
                    handler.postDelayed(()->{if(currentActivity==this&&isFinishing()&&android.app.Application.getProcessName().endsWith(":reconstruction"))android.os.Process.killProcess(android.os.Process.myPid());},1000);
                }
            });
        },"LocalReconstruction").start();
        handler.post(this::poll);
    }
    void poll(){
        try{
            String text=new File(output,"progress.json").exists()?read(new File(output,"progress.json")):"正在准备图像和引擎…";
            JSONObject metric=new JSONObject();metric.put("elapsed_ms",SystemClock.elapsedRealtime()-started);metric.put("pss_kb",Debug.getPss());
            metric.put("native_allocated_bytes",Debug.getNativeHeapAllocatedSize());
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);metric.put("thermal_status",power.getCurrentThermalStatus());
            Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(battery!=null)metric.put("battery_temperature_c",battery.getIntExtra("temperature",0)/10.0);
            try(FileWriter log=new FileWriter(new File(output,"metrics.jsonl"),true)){log.write(metric.toString()+"\n");}
            if(running)status.setText(describe(text)+"\n\n已运行 "+((SystemClock.elapsedRealtime()-started)/1000)+" 秒\n应用内存约 "+(Debug.getPss()/1024)+" MB\n电池温度 "+metric.optDouble("battery_temperature_c")+" ℃");
        }catch(Exception error){android.util.Log.w("FurnitureProbe","Progress",error);}
        if(running)handler.postDelayed(this::poll,1500);
    }
    public static JSONObject qualityConfig()throws Exception{return new JSONObject().put("preset","balanced-v0121").put("total_steps",2400).put("disable_refinement",false).put("refine_every",100).put("subsample_frames",1).put("resolution",768).put("max_splats",24000).put("growth_stop_iter",1400).put("growth_grad_threshold",0.00003).put("growth_select_fraction",0.12).put("export_every",400).put("budget_seconds",600);}
    void confirmCancel(){if(!running)return;StudioDialog.confirm(this,"取消本次生成？",StudioIcon.Kind.PHONE,"原始照片和已有模型会保留，可稍后重新生成。","继续生成","取消生成",true,this::requestCancel);}
    public void requestCancel(){if(!running)return;
        final File task=output;
        try(FileWriter flag=new FileWriter(new File(task,"CANCEL"))){flag.write("user");status.setText("正在停止计算…");}catch(IOException error){status.setText("停止请求未写入："+error.getMessage());return;}
        cancelRequested=true;cancel.setEnabled(false);
        handler.postDelayed(()->{if(running&&task.equals(output)&&android.app.Application.getProcessName().endsWith(":reconstruction")){
            try{String message=new JSONObject().put("phase","cancelled").put("error","User cancelled; stopped unresponsive worker").toString();for(String name:new String[]{"progress.json","integration-result.json"})try(FileWriter f=new FileWriter(new File(task,name))){f.write(message);}}catch(Exception ignored){}
            android.os.Process.killProcess(android.os.Process.myPid());
        }},15000);
    }
    @Override public void onBackPressed(){if(running){confirmCancel();return;}super.onBackPressed();}
}
