package com.furniture.chairdemo;
import android.app.*;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

final class FurnitureLibrary {
    static void row(MainActivity a,LinearLayout list,String name,String note,StudioIcon.Kind icon,Runnable open,Runnable actions){
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,0,a.dp(4),0);row.setBackground(Ui.surface(a,Ui.PANEL,14));
        Button button=Ui.cardAction(a,name,note,icon);
        button.setOnClickListener(v->open.run());
        row.addView(button,new LinearLayout.LayoutParams(0,-2,1));
        if(actions!=null){
            Button menu=Ui.plain(a,"···");menu.setTextSize(21);
            menu.setContentDescription("更多操作 "+name);menu.setOnClickListener(v->actions.run());
            row.addView(menu,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));
        }
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=a.dp(7);list.addView(row,params);
    }
    private static void itemActions(MainActivity a,String name,String kind,NameDialog.Save rename,Runnable delete){
        StudioDialog menu=StudioDialog.panel(a,name,StudioIcon.Kind.TOOLS);
        menu.choice(StudioIcon.Kind.EDIT,"重命名","",()->{menu.dismiss();NameDialog.show(a,"重命名"+kind,name,"输入新名称","取消",false,rename);});
        menu.choice(StudioIcon.Kind.DELETE,"删除","",()->{menu.dismiss();delete.run();});
        menu.actions("取消",null,null,null,false);menu.show();
    }
    static void confirmDelete(MainActivity a,Dialog library,String name,String modelId,File task){
        StudioDialog.confirm(a,"删除"+(task==null?"家具":"三维场景")+"？",StudioIcon.Kind.DELETE,"「"+name+"」将从素材库移除。原始拍摄记录会保留。","取消","删除",true,()->{
            library.dismiss();StudioDialog progress=StudioDialog.progress(a,"正在删除","正在从素材库移除…");
            new Thread(()->{try{if(task==null)LibraryStore.deleteModel(a,modelId);else LibraryStore.deleteScene(a,task);
                a.runOnUiThread(()->{progress.dismiss();if(a.isFinishing())return;Toast.makeText(a,"已删除",Toast.LENGTH_SHORT).show();if(modelId!=null&&modelId.equals(a.activeModelId))a.selectModel(ModelStore.BUILTIN);else show(a,false);});
            }catch(Exception error){a.runOnUiThread(()->{progress.dismiss();if(!a.isFinishing())StudioDialog.confirm(a,"删除未完成",null,error.getMessage(),null,"知道了",false,()->show(a,false));});}},"DeleteLibraryItem").start();
        });
    }
    private static void section(MainActivity a,LinearLayout list,String label){
        TextView title=Ui.heading(a,label,16);title.setPadding(a.dp(2),list.getChildCount()==0?0:a.dp(17),0,a.dp(8));list.addView(title);
    }
    private static void empty(MainActivity a,LinearLayout list,String label){
        TextView note=Ui.text(a,label,13,Ui.MUTED);note.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(12));list.addView(note);
    }
    static void show(MainActivity a,boolean room){
        if(room){
            StudioDialog chooser=StudioDialog.panel(a,"选择家具",StudioIcon.Kind.MODEL);
            addModels(a,chooser.body,chooser,true,null);
            chooser.actions("关闭",null,null,null,false);
            chooser.show();
            return;
        }
        StudioDialog library=StudioDialog.page(a,"素材库");
        fillLibrary(a,library.body,library);
        library.show();
    }
    private static void fillLibrary(MainActivity a,LinearLayout list,Dialog library){
        list.removeAllViews();
        Runnable refresh=()->fillLibrary(a,list,library);
        section(a,list,"家具模型");
        addModels(a,list,library,false,refresh);
        section(a,list,"三维场景");
        addScenes(a,list,library,refresh);
        section(a,list,"房间布局");
        try{
            ArrayList<JSONObject> layouts=new RoomLayoutStore(a.getFilesDir()).list();
            if(layouts.isEmpty())empty(a,list,"在房间内保存后，布局会出现在这里。");
            for(JSONObject layout:layouts)row(a,list,layout.optString("name"),layout.getJSONArray("items").length()+" 件家具",StudioIcon.Kind.LAYOUT,
                    ()->{library.dismiss();RoomLayouts.actions(a,layout);},null);
        }catch(Exception error){empty(a,list,"暂时无法读取房间布局。");}
    }
    private static void addModels(MainActivity a,LinearLayout list,Dialog dialog,boolean room,Runnable refresh){
        ArrayList<JSONObject> models=ModelStore.list(a);try{models.add(ModelStore.metadata(a,ModelStore.BUILTIN));}catch(Exception ignored){}
        for(JSONObject m:models){String id=m.optString("id");boolean ready=!"cropped_furniture_pending".equals(m.optString("kind"));if(room&&!ready)continue;
            String name=ModelStore.BUILTIN.equals(id)?"示例椅子":m.optString("name","家具");String state=ready?"可放进房间":"待校准尺寸";
            Runnable actions=room||ModelStore.BUILTIN.equals(id)?null:()->itemActions(a,name,"家具",replacement->{
                LibraryNames.renameFurniture(a,m,replacement);
                if(a.activeModel!=null&&!ModelStore.BUILTIN.equals(a.activeModelId)){
                    a.activeModel.put("name",LibraryNames.furniture(a,a.activeModel));a.ui.refresh();
                }
                refresh.run();
            },()->confirmDelete(a,dialog,name,id,null));
            row(a,list,name,state,StudioIcon.Kind.MODEL,()->{dialog.dismiss();if(room)a.addRoomModel(id,false);else a.selectModel(id);},actions);
        }
    }
    private static void addScenes(MainActivity a,LinearLayout list,Dialog dialog,Runnable refresh){
        File[] tasks=a.getFilesDir().listFiles((d,name)->name.matches("probe-\\d+-1200"));int scenes=0;
        if(tasks!=null){Arrays.sort(tasks,(l,r)->r.getName().compareTo(l.getName()));for(File task:tasks)try{
            if(LibraryStore.taskDeleted(a,task))continue;JSONObject result=ModelStore.json(new File(task,"integration-result.json"));
            if(!"completed".equals(result.optString("phase"))||!result.optString("capture_id").matches("scan-\\d+-[a-f0-9]{8}"))continue;
            scenes++;String label=LibraryNames.scene(a,task.getName(),result.optString("capture_id"),result.optString("source"));
            String source="computer".equals(result.optString("source"))?"电脑重建":"手机重建";
            row(a,list,label,source+" · 查看与裁剪",StudioIcon.Kind.SCAN,()->{dialog.dismiss();SceneViewerActivity.open(a,task);},
                    ()->itemActions(a,label,"三维场景",replacement->{LibraryNames.renameScene(a,task,replacement);refresh.run();},
                    ()->confirmDelete(a,dialog,label,null,task)));
        }catch(Exception ignored){}}
        if(scenes==0)empty(a,list,"完成生成后，三维场景会保存在这里。");
    }
    static void chooseGeneration(Activity a,String capture){
        StudioDialog options=StudioDialog.panel(a,"选择生成方式",StudioIcon.Kind.MODEL);
        options.choice(StudioIcon.Kind.COMPUTER,"电脑生成","连接同一 Wi-Fi",()->{
            options.dismiss();a.startActivity(new android.content.Intent(a,ComputerActivity.class).putExtra("capture_id",capture));
        });
        options.choice(StudioIcon.Kind.PHONE,"手机生成","本机处理 · 最长十分钟",()->{
            StudioDialog.confirm(a,"手机生成家具",StudioIcon.Kind.PHONE,"请保持生成页面打开，生成过程中可以随时取消。","返回","开始生成",false,()->{
                options.dismiss();a.startActivity(new android.content.Intent().setClassName(a.getPackageName(),"com.furniture.reconstructionprobe.MainActivity").putExtra("capture_id",capture).putExtra("auto_generate",true));
            });
        });
        options.actions("取消",null,null,null,false);options.show();
    }
    static void generate(MainActivity a){
        StudioDialog page=StudioDialog.page(a,"生成模型");
        section(a,page.body,"选择拍摄记录");
        boolean hasReady=false;
        for(JSONObject capture:CaptureData.sessions(a.getFilesDir())){
            int count=capture.optInt("committed_frames");
            if(!"completed".equals(capture.optString("state"))||count<20)continue;
            hasReady=true;
            String id=capture.optString("id");
            page.choice(StudioIcon.Kind.SCAN,LibraryNames.capture(a,id),count+" 张画面",()->chooseGeneration(a,id));
        }
        if(!hasReady){
            page.empty("还没有可生成的拍摄记录","保存至少 20 张有效画面后，即可生成模型。");
            page.actions("电脑重建",()->a.startActivity(new android.content.Intent(a,ComputerActivity.class)),
                    "去扫描",()->{page.dismiss();a.startActivity(new android.content.Intent(a,ScanActivity.class));},false);
        }else{
            page.actions("连接电脑与查看任务",()->a.startActivity(new android.content.Intent(a,ComputerActivity.class)),null,null,false);
        }
        page.show();
    }
}
