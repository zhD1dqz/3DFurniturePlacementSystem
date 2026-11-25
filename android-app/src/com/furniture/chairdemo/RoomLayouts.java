package com.furniture.chairdemo;
import java.util.*;
import org.json.*;

final class RoomLayouts {
    static void title(MainActivity a,String heading,String original,NameDialog.Save action){NameDialog.show(a,heading,original,"例如：卧室、客厅","取消",false,action);}
    static void save(MainActivity a){save(a,null);}
    static void save(MainActivity a,Runnable after){if(a.session==null){a.message("请先进入房间并放置家具");return;}title(a,"保存当前房间布局","房间布局",name->a.surface.queueEvent(()->{try{JSONArray items=a.renderer.room.snapshot();new RoomLayoutStore(a.getFilesDir()).save(name,items);a.message("房间布局已保存；下次恢复时重新选择地面参考位置");if(after!=null)a.runOnUiThread(after);}catch(Exception e){a.message("保存失败："+e.getMessage());}}));}
    static void show(MainActivity a){
        StudioDialog page=StudioDialog.page(a,"房间布局");
        try{
            ArrayList<JSONObject> all=new RoomLayoutStore(a.getFilesDir()).list();
            if(all.isEmpty())page.empty("还没有房间布局","摆放家具后，可以从房间工具栏保存布局。");
            for(JSONObject item:all)page.choice(StudioIcon.Kind.LAYOUT,item.getString("name"),
                    item.getJSONArray("items").length()+" 件家具",()->{page.dismiss();actions(a,item);});
        }catch(Exception error){page.empty("无法读取布局",error.getMessage());}
        page.show();
    }
    static void actions(MainActivity a,JSONObject j){
        String id=j.optString("id"),name=j.optString("name");
        StudioDialog menu=StudioDialog.panel(a,name,StudioIcon.Kind.LAYOUT);
        menu.choice(StudioIcon.Kind.LAYOUT,"恢复到房间","",()->{
            menu.dismiss();StudioDialog.confirm(a,"恢复房间布局",StudioIcon.Kind.LAYOUT,
                    "恢复后会替换当前摆放。点击地面指定第一件家具的位置，其余家具会按原间距出现。",
                    "取消","继续",false,()->a.prepareLayout(id));
        });
        menu.choice(StudioIcon.Kind.EDIT,"重命名","",()->{
            menu.dismiss();title(a,"重命名布局",name,replacement->new RoomLayoutStore(a.getFilesDir()).rename(id,replacement));
        });
        menu.choice(StudioIcon.Kind.DELETE,"删除布局","",()->{
            menu.dismiss();StudioDialog.confirm(a,"删除布局？",StudioIcon.Kind.DELETE,"「"+name+"」的摆放记录将被删除。家具模型会保留。",
                    "取消","删除",true,()->{try{new RoomLayoutStore(a.getFilesDir()).remove(id);a.message("布局已删除");}catch(Exception e){a.message(e.getMessage());}});
        });
        menu.actions("关闭",null,null,null,false);menu.show();
    }
}
