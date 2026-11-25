package com.furniture.chairdemo;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** LAN upload, durable job polling and checksum-verified Gaussian model import. */
public class ComputerActivity extends Activity {
    final Handler handler=new Handler(Looper.getMainLooper());
    TextView status,connection;Button start,open,cancel,retry,history,refresh,scan,health;
    volatile boolean busy=false,closed=false;File localTask;String captureId;
    android.content.SharedPreferences prefs(){return getSharedPreferences("computer-processing",0);}
    void show(String text){handler.post(()->{if(!closed)status.setText(text);});}
    void resumeJob(){if(busy)return;if(!prefs().contains("job_id")){show("尚无电脑处理任务，请先选择扫描上传，或打开电脑任务历史。");return;}busy=true;controls();new Thread(()->monitor(),"RefreshComputerJob").start();}
    void checkConnection(){if(busy)return;busy=true;controls();show("正在检测电脑连接…");new Thread(()->{try{JSONObject h=request("GET","/api/health",null,false);if(h.optInt("protocol")!=1)throw new IOException("电脑服务版本不匹配");if(!h.optString("server_id").equals(prefs().getString("server_id","")))throw new IOException("电脑服务身份已变化，请重新配对");request("GET","/api/jobs",null,true);show("连接正常 · "+(h.optBoolean("busy")?"电脑正在处理任务，可从任务历史查看":"电脑空闲，可以上传扫描"));}catch(Exception e){show("连接检测失败："+friendly(e));}finally{busy=false;controls();}},"CheckComputer").start();}
    void history(){if(busy)return;busy=true;controls();new Thread(()->{try{
        JSONArray jobs=request("GET","/api/jobs",null,true).getJSONArray("jobs");
        handler.post(()->{if(!closed)showHistory(jobs);});
        }catch(Exception e){show("无法读取任务历史："+friendly(e));}finally{busy=false;controls();}},"ComputerHistory").start();}
    private void showHistory(JSONArray jobs){
        StudioDialog page=StudioDialog.page(this,"电脑任务历史");
        if(jobs.length()==0)page.empty("暂无电脑任务","上传一份拍摄记录后，可以在这里查看重建进度。");
        for(int i=0;i<jobs.length();i++){
            JSONObject j=jobs.optJSONObject(i);if(j==null)continue;
            String state=j.optString("status"),label="completed".equals(state)?"已完成":"failed".equals(state)?"失败":"cancelled".equals(state)?"已取消":"处理中";
            page.choice(StudioIcon.Kind.HISTORY,LibraryNames.capture(this,j.optString("capture_id")),label+" · "+j.optString("message"),
                    ()->{page.dismiss();resumeHistoryItem(j);});
        }
        page.show();
    }
    private void resumeHistoryItem(JSONObject job){
        try{
            String id=job.getString("id"),capture=job.getString("capture_id");
            if(!id.matches("[a-f0-9]{32}")||!capture.matches("scan-\\d+-[a-f0-9]{8}"))throw new IOException("任务编号无效");
            String local=prefs().getString("task_"+id,"");
            prefs().edit().putString("job_id",id).putString("job_capture",capture).remove("local_task").commit();
            localTask=null;
            if(local.matches("probe-\\d+-1200"))prefs().edit().putString("local_task",local).commit();
            captureId=capture;resumeJob();
        }catch(Exception error){show(error.getMessage());}
    }
    void controls(){handler.post(()->{if(closed)return;start.setEnabled(!busy&&captureId!=null);open.setEnabled(localTask!=null);cancel.setEnabled(busy&&prefs().contains("job_id"));cancel.setVisibility(busy&&prefs().contains("job_id")?android.view.View.VISIBLE:android.view.View.GONE);start.setVisibility(!busy&&localTask==null?android.view.View.VISIBLE:android.view.View.GONE);open.setVisibility(localTask!=null?android.view.View.VISIBLE:android.view.View.GONE);retry.setVisibility(!busy&&localTask!=null?android.view.View.VISIBLE:android.view.View.GONE);for(Button b:new Button[]{scan,health,history,refresh})if(b!=null)b.setEnabled(!busy);connection.setText(prefs().contains("token")?"已连接 · "+prefs().getString("url",""):"连接电脑后即可无线处理");});}
    private LinearLayout card(LinearLayout content,String title){
        LinearLayout card=Ui.column(this);
        int inset=Ui.dp(this,13);
        card.setPadding(inset,inset,inset,Ui.dp(this,7));card.setBackground(Ui.surface(this,Ui.PANEL,17));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=Ui.dp(this,10);
        content.addView(card,params);card.addView(Ui.heading(this,title,16));return card;
    }
    private Button link(LinearLayout row,String label,Runnable action){
        Button button=Ui.plain(this,label);button.setTextSize(13);button.setOnClickListener(v->action.run());
        row.addView(button,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));return button;
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);captureId=getIntent().getStringExtra("capture_id");
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        root.addView(Ui.toolbar(this,"电脑重建",this::finish));
        Ui.divider(root);
        ScrollView scroll=new ScrollView(this);LinearLayout content=Ui.column(this);
        content.setPadding(Ui.dp(this,4),Ui.dp(this,10),Ui.dp(this,4),0);scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout connectionCard=card(content,"连接电脑");
        connection=Ui.text(this,"",13,Ui.MUTED);connection.setPadding(0,Ui.dp(this,7),0,Ui.dp(this,5));connectionCard.addView(connection);
        LinearLayout links=new LinearLayout(this);connectionCard.addView(links);
        scan=link(links,"扫码连接",()->{if(!busy)startActivityForResult(new Intent(this,PairScannerActivity.class),41);});
        link(links,"手动配对",()->{if(!busy)configure();});
        health=link(links,"检测连接",this::checkConnection);
        LinearLayout taskCard=card(content,"重建任务");
        status=Ui.text(this,captureId==null?"选择一份已保存的扫描后，即可上传。":"扫描已就绪，可以上传到电脑。",14,Ui.MUTED);
        status.setPadding(0,Ui.dp(this,9),0,Ui.dp(this,10));taskCard.addView(status);
        Ui.divider(taskCard);
        LinearLayout tasks=new LinearLayout(this);taskCard.addView(tasks);
        history=link(tasks,"任务历史",this::history);
        refresh=link(tasks,"继续查询",this::resumeJob);
        start=Ui.button(this,"上传扫描并在电脑生成",true);start.setOnClickListener(v->{if(!prefs().contains("token")){configure();return;}begin();});root.addView(start,Ui.margin(this,-1,52));
        open=Ui.button(this,"查看三维模型并裁剪",true);open.setOnClickListener(v->{if(localTask!=null)SceneViewerActivity.open(this,localTask);});root.addView(open,Ui.margin(this,-1,52));
        retry=Ui.plain(this,"重新生成这次扫描");retry.setOnClickListener(v->StudioDialog.confirm(this,"重新生成？",StudioIcon.Kind.COMPUTER,"电脑会重新处理这组扫描，已有模型会保留。","取消","重新生成",false,this::begin));root.addView(retry,Ui.margin(this,-1,48));
        cancel=Ui.button(this,"取消电脑处理",false);cancel.setOnClickListener(v->StudioDialog.confirm(this,"取消电脑处理？",StudioIcon.Kind.COMPUTER,"原始扫描和已有模型会保留。","继续处理","取消处理",true,
                ()->new Thread(()->{try{request("POST","/api/jobs/"+prefs().getString("job_id","")+"/cancel",new byte[0],true);}catch(Exception error){show("取消请求未送达："+error.getMessage());}}).start()));root.addView(cancel,Ui.margin(this,-1,48));
        setContentView(root);root.setOnApplyWindowInsetsListener((v,insets)->{int padding=Ui.dp(this,14);v.setPadding(insets.getSystemWindowInsetLeft()+padding,insets.getSystemWindowInsetTop()+Ui.dp(this,6),insets.getSystemWindowInsetRight()+padding,insets.getSystemWindowInsetBottom()+Ui.dp(this,10));return insets;});root.requestApplyInsets();
        String migrated=prefs().getString("local_task","");String migratedJob=prefs().getString("job_id","");if(migratedJob.matches("[a-f0-9]{32}")&&migrated.matches("probe-\\d+-1200")&&!prefs().contains("task_"+migratedJob))prefs().edit().putString("task_"+migratedJob,migrated).commit();String task=prefs().getString("local_task","");if((captureId==null||captureId.equals(prefs().getString("job_capture",null)))&&task.matches("probe-\\d+-1200")){File f=new File(getFilesDir(),task);if(f.exists()&&!LibraryStore.taskDeleted(this,f))localTask=f;}
        String pending=prefs().getString("job_capture",null);if(prefs().contains("job_id")&&(captureId==null||captureId.equals(pending))){if(captureId==null)captureId=pending;busy=true;controls();new Thread(()->monitor(),"ResumeComputerJob").start();}else controls();
    }
    static String normalizeUrl(String input)throws Exception{
        String value=input.trim();if(!value.startsWith("http://"))value="http://"+value;URI uri=new URI(value);
        if(!"http".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getHost()==null||!uri.getHost().matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))throw new IOException("请输入电脑页面上的局域网地址");
        InetAddress address=InetAddress.getByName(uri.getHost());if(!address.isSiteLocalAddress()&&!address.isLoopbackAddress())throw new IOException("当前版本仅支持同一局域网的电脑");int port=uri.getPort()<0?8268:uri.getPort();if(port<1||port>65535)throw new IOException("端口无效");return "http://"+uri.getHost()+":"+port;
    }
    void configure(){
        StudioDialog dialog=StudioDialog.panel(this,"手动配对",StudioIcon.Kind.COMPUTER);
        dialog.body.addView(Ui.text(this,"电脑地址",13,Ui.MUTED));Ui.space(dialog.body,6);
        EditText address=Ui.field(this,prefs().getString("url",""),"如 192.168.1.30:8268",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        dialog.body.addView(address);Ui.space(dialog.body,12);
        dialog.body.addView(Ui.text(this,"连接码",13,Ui.MUTED));Ui.space(dialog.body,6);
        EditText code=Ui.field(this,"","电脑页面上的 8 位连接码",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        dialog.body.addView(code);
        TextView error=Ui.text(this,"",13,Ui.DANGER);error.setPadding(0,Ui.dp(this,6),0,0);error.setVisibility(android.view.View.GONE);dialog.body.addView(error);
        dialog.actions("取消",null,"连接",()->{
            try{
                String url=normalizeUrl(address.getText().toString()),secret=code.getText().toString();
                if(!secret.matches("[0-9]{8}"))throw new IOException("请输入 8 位连接码");
                dialog.dismiss();pair(url,secret);
            }catch(Exception problem){error.setText(problem.getMessage());error.setVisibility(android.view.View.VISIBLE);}
        },false);
        dialog.show();
    }
    void pair(String url,String code){busy=true;controls();show("正在连接电脑…");new Thread(()->{try{
        JSONObject result=requestAt(url,"POST","/api/pair",new JSONObject().put("code",code).toString().getBytes("UTF-8"),false);
        if(!result.getString("token").matches("[A-Za-z0-9_-]{32,100}"))throw new IOException("电脑返回的连接信息无效");
        android.content.SharedPreferences.Editor edit=prefs().edit();if(!result.getString("server_id").equals(prefs().getString("server_id",""))){for(String key:prefs().getAll().keySet())if(key.startsWith("task_"))edit.remove(key);edit.remove("job_id").remove("job_capture").remove("local_task");localTask=null;}
        edit.putString("url",url).putString("token",result.getString("token")).putString("server_id",result.getString("server_id")).commit();show("电脑已连接。可以上传这次扫描。\n电脑处理最长十分钟，传输进度会单独显示。");
    }catch(Exception error){show("连接失败："+friendly(error));}finally{busy=false;controls();}},"PairComputer").start();}
    String friendly(Exception error){if(error instanceof SocketTimeoutException||error instanceof ConnectException||error instanceof NoRouteToHostException)return "请检查电脑服务已启动、两台设备在同一Wi-Fi，并允许电脑服务通过防火墙。";return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    HttpURLConnection connect(String base,String method,String path,boolean auth)throws Exception{
        String safe=normalizeUrl(base);if(!path.matches("/api/(pair|health|jobs|jobs/[a-f0-9]{32}(/model|/cancel)?)"))throw new IOException("接口路径无效");
        HttpURLConnection c=(HttpURLConnection)new URL(safe+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);if(auth)c.setRequestProperty("Authorization","Bearer "+prefs().getString("token",""));return c;
    }
    JSONObject response(HttpURLConnection c)throws Exception{int code=c.getResponseCode();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();if(stream==null)throw new IOException("电脑返回 "+code);String text=ModelStore.text(stream);JSONObject value=new JSONObject(text);if(code<200||code>=300)throw new IOException(code==401?"连接授权已失效，请重新连接电脑":value.optString("error","电脑返回 "+code));return value;}
    JSONObject requestAt(String base,String method,String path,byte[] body,boolean auth)throws Exception{HttpURLConnection c=connect(base,method,path,auth);try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}return response(c);}finally{c.disconnect();}}
    JSONObject request(String method,String path,byte[] body,boolean auth)throws Exception{return requestAt(prefs().getString("url",""),method,path,body,auth);}
    File archive(String id)throws Exception{
        if(id==null||!id.matches("scan-\\d+-[a-f0-9]{8}"))throw new IOException("请先选择已完成的扫描");File folder=new File(getFilesDir(),"captures/"+id);if(!folder.isDirectory())throw new IOException("这台手机没有该任务的原始扫描；可查看电脑已有结果，重新生成需要在原采集手机操作");JSONObject manifest=ModelStore.json(new File(folder,"capture.json"));if(!"completed".equals(manifest.optString("state")))throw new IOException("请先结束并保存扫描");
        File[] records=folder.listFiles((d,n)->n.matches("frame-\\d{4}\\.json"));if(records==null||records.length<20||records.length>300)throw new IOException("需要20至300张有效画面");Arrays.sort(records);File temp=new File(getCacheDir(),"computer-upload-"+UUID.randomUUID()+".zip");
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(temp))){out.setLevel(0);addFile(out,new File(folder,"capture.json"));for(File record:records){JSONObject frame=ModelStore.json(record);String name=frame.getString("image");if(!name.matches("frame-\\d{4}\\.jpg"))throw new IOException("扫描照片名无效");File image=new File(folder,name);if(!PlyModelImporter.sha(image).equals(frame.getString("image_sha256")))throw new IOException("扫描照片校验失败");addFile(out,record);addFile(out,image);}}return temp;
    }
    void addFile(ZipOutputStream zip,File file)throws Exception{zip.putNextEntry(new ZipEntry(file.getName()));try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);}zip.closeEntry();}
    void begin(){if(busy)return;busy=true;localTask=null;controls();new Thread(()->{File zip=null;try{
        String previous=prefs().getString("job_id","");if(previous.matches("[a-f0-9]{32}")&&!prefs().contains("local_task")){
            try{JSONObject existing=request("GET","/api/jobs/"+previous,null,true);String state=existing.optString("status");if(!"failed".equals(state)&&!"cancelled".equals(state)){captureId=existing.getString("capture_id");show("已恢复之前的电脑处理任务…");monitor();return;}}
            catch(IOException error){if(!error.getMessage().contains("未找到任务"))throw error;}
        }
        show("正在整理扫描照片…");zip=archive(captureId);if(zip.length()>512L*1024*1024)throw new IOException("照片包较大，请减少重复照片");String id=UUID.randomUUID().toString().replace("-","");prefs().edit().putString("job_id",id).putString("job_capture",captureId).remove("local_task").commit();controls();
        HttpURLConnection c=connect(prefs().getString("url",""),"PUT","/api/jobs/"+id,true);try{c.setDoOutput(true);c.setRequestProperty("Content-Type","application/zip");c.setRequestProperty("X-Capture-Id",captureId);c.setRequestProperty("X-Upload-Sha256",PlyModelImporter.sha(zip));c.setFixedLengthStreamingMode(zip.length());
            try(InputStream in=new FileInputStream(zip);OutputStream out=c.getOutputStream()){byte[] buffer=new byte[65536];int n,last=-1;long sent=0;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);sent+=n;int percent=(int)(100*sent/zip.length());if(percent!=last){last=percent;show("正在上传扫描 · "+percent+"%\n"+String.format(Locale.CHINA,"%.1f / %.1f MB",sent/1048576.0,zip.length()/1048576.0));}}}response(c);
        }finally{c.disconnect();}
        monitor();
    }catch(Exception error){show("上传未完成："+friendly(error)+"\n重新进入本页可查询电脑是否已收到任务。");busy=false;controls();}finally{if(zip!=null)zip.delete();}},"UploadCapture").start();}
    void monitor(){try{String id=prefs().getString("job_id","");if(!id.matches("[a-f0-9]{32}"))throw new IOException("没有待处理任务");
        int misses=0;while(!closed){JSONObject value;try{value=request("GET","/api/jobs/"+id,null,true);misses=0;}catch(SocketTimeoutException|ConnectException|NoRouteToHostException temporary){if(++misses>3)throw temporary;show("连接暂时中断，正在重试查询（"+misses+"/3）…\n电脑任务不会因此重新训练。");Thread.sleep(2000L*misses);continue;}if(!captureId.equals(value.getString("capture_id")))throw new IOException("电脑任务与扫描不一致");String phase=value.getString("status");
            if("completed".equals(phase)){String task=prefs().getString("local_task","");if(task.matches("probe-\\d+-1200")&&LibraryStore.taskDeleted(this,new File(getFilesDir(),task))){show("这份电脑模型已在手机删除，可选择扫描重新生成。");break;}if(task.matches("probe-\\d+-1200")&&new File(getFilesDir(),task).exists()){localTask=new File(getFilesDir(),task);if(LibraryStore.taskDeleted(this,localTask))localTask=null;}if(localTask==null)localTask=findLocalTask(id);if(localTask==null)localTask=download(value);show("电脑模型已下载并保存。\n处理用时约 "+Math.round(value.getDouble("elapsed_seconds"))+" 秒 · "+value.getInt("gaussians")+" 个高斯\n可以查看、裁剪家具并校准尺寸，再放进房间。"+(value.optBoolean("stopped_for_time_budget")?"\n本次达到预算，保存了当前可用模型。":""));break;}
            if("failed".equals(phase)||"cancelled".equals(phase)){show(value.optString("message","本次处理已结束"));break;}
            long elapsed=Math.max(0,(System.currentTimeMillis()-value.optLong("started_at_ms",System.currentTimeMillis()))/1000);show(value.optString("message","电脑处理中")+"\n已运行约 "+elapsed+" 秒\n可返回首页；电脑会继续处理，重新进入本页可查看结果。");Thread.sleep(1800);
        }
    }catch(Exception error){show("暂时无法查询电脑："+friendly(error)+"\n电脑可能仍在处理，重新进入本页可继续查询。");}finally{busy=false;controls();}}
    File findLocalTask(String id)throws Exception{
        File[] tasks=getFilesDir().listFiles((d,n)->n.matches("probe-\\d+-1200"));if(tasks==null)return null;
        for(File task:tasks)try{if(LibraryStore.taskDeleted(this,task))continue;JSONObject r=ModelStore.json(new File(task,"integration-result.json"));if(id.equals(r.optString("computer_job_id"))&&"completed".equals(r.optString("phase"))){SceneStore.prepare(this,task);prefs().edit().putString("local_task",task.getName()).putString("task_"+id,task.getName()).commit();return task;}}catch(Exception ignored){}return null;
    }
    File download(JSONObject job)throws Exception{
        String id=job.getString("id"),expected=job.getString("model_sha256");long bytes=job.getLong("model_bytes");int steps=job.getInt("completed_steps");if(!id.matches("[a-f0-9]{32}")||!expected.matches("[a-f0-9]{64}")||bytes<1024||bytes>256L*1024*1024||steps<1||steps>30000)throw new IOException("电脑模型信息无效");
        File pending=new File(getFilesDir(),".computer-pending-"+UUID.randomUUID());if(!pending.mkdir())throw new IOException("无法保存模型");File ply=new File(pending,"generated_"+steps+".ply");try{
        HttpURLConnection c=connect(prefs().getString("url",""),"GET","/api/jobs/"+id+"/model",true);try{if(c.getResponseCode()!=200||c.getContentLengthLong()!=bytes)throw new IOException("模型下载信息不一致");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(ply)){byte[] buffer=new byte[65536];int n,last=-1;long count=0;while((n=in.read(buffer))!=-1){if(closed)throw new IOException("已退出下载页面，可稍后继续查询并重新下载");count+=n;if(count>bytes)throw new IOException("下载模型超出预期大小");out.write(buffer,0,n);int percent=(int)(count*100/bytes);if(percent!=last){last=percent;show("正在下载模型 · "+percent+"%");}}if(count!=bytes)throw new IOException("模型下载中断");}}finally{c.disconnect();}
        if(!PlyModelImporter.sha(ply).equals(expected))throw new IOException("模型校验失败，请重新下载");JSONObject result=new JSONObject(job.toString()).put("phase","completed").put("model",ply.getName()).put("generation_and_prepare_ms",Math.round(job.getDouble("elapsed_seconds")*1000)).put("source","computer").put("computer_job_id",id).put("capture_id",captureId);
        CaptureData.atomic(new File(pending,"result.json"),result);CaptureData.atomic(new File(pending,"integration-result.json"),result);File published=new File(getFilesDir(),"probe-"+System.currentTimeMillis()+"-1200");if(!pending.renameTo(published))throw new IOException("无法完成模型保存");
        try{show("正在整理三维模型…");SceneStore.prepare(this,published);}catch(Exception error){result.put("phase","failed").put("error",error.toString());CaptureData.atomic(new File(published,"integration-result.json"),result);throw error;}
        prefs().edit().putString("local_task",published.getName()).putString("task_"+id,published.getName()).commit();return published;}finally{if(pending.exists())new LibraryStore(getFilesDir()).erase(pending);}
    }
    @Override protected void onDestroy(){closed=true;super.onDestroy();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==41&&result==RESULT_OK&&data!=null)try{String[] parsed=PairingCode.parse(data.getStringExtra("pairing"));StudioDialog.confirm(this,"连接这台电脑？",StudioIcon.Kind.COMPUTER,parsed[0],"取消","连接",false,()->pair(parsed[0],parsed[1]));}catch(Exception e){show(e.getMessage());}}
}
