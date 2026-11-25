package com.furniture.chairdemo;
import java.io.*;
import java.util.*;
import org.json.*;

/** Durable furniture arrangements relative to an explicit ground reference. */
final class RoomLayoutStore {
    final File root;
    RoomLayoutStore(File files){root=new File(files,"room-layouts");}
    File file(String id)throws IOException{if(id==null||!id.matches("[a-f0-9]{32}"))throw new IOException("布局编号无效");File base=root.getParentFile().getCanonicalFile();File expected=new File(base,"room-layouts");if(!root.getCanonicalFile().equals(expected))throw new IOException("布局目录包含链接");File f=new File(expected,id+".json");if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("布局路径包含链接");return f;}
    static String name(String n)throws IOException{String title=n==null?"":n.trim();if(title.isEmpty()||title.codePointCount(0,title.length())>40)throw new IOException("名称需为1至40个字符");return title;}
    static void validate(JSONObject layout)throws Exception{
        if(layout.getInt("schema_version")!=1)throw new IOException("布局版本不支持");name(layout.getString("name"));
        JSONArray entries=layout.getJSONArray("items");if(entries.length()<1||entries.length()>20)throw new IOException("布局需包含1至20件家具");
        for(int i=0;i<entries.length();i++){
            JSONObject j=entries.getJSONObject(i);String id=j.getString("model_id");if(!ModelStore.BUILTIN.equals(id)&&!id.matches("[a-f0-9]{64}"))throw new IOException("布局模型编号无效");
            for(String k:new String[]{"width","depth","height"}){double x=j.getDouble(k);if(!Double.isFinite(x)||x<.05||x>10)throw new IOException("布局尺寸超出范围");}
            double contact=j.getDouble("contact"),yaw=j.getDouble("yaw");if(!Double.isFinite(contact)||Math.abs(contact)>.051||!Double.isFinite(yaw))throw new IOException("布局调整参数无效");
            JSONArray t=j.getJSONArray("translation"),q=j.getJSONArray("rotation");if(t.length()!=3||q.length()!=4)throw new IOException("布局姿态无效");double norm=0;
            for(int k=0;k<3;k++)if(!Double.isFinite(t.getDouble(k))||Math.abs(t.getDouble(k))>50)throw new IOException("布局距离超出范围");
            for(int k=0;k<4;k++){double v=q.getDouble(k);if(!Double.isFinite(v))throw new IOException("布局旋转无效");norm+=v*v;}if(Math.abs(norm-1)>.01)throw new IOException("布局旋转无效");
            if(!Arrays.asList("height","axes","fit").contains(j.getString("size_mode")))throw new IOException("布局尺寸模式无效");
        }
    }
    synchronized JSONObject save(String title,JSONArray entries)throws Exception{
        String id=UUID.randomUUID().toString().replace("-","");JSONObject j=new JSONObject().put("schema_version",1).put("id",id).put("name",name(title)).put("created_at_ms",System.currentTimeMillis()).put("items",entries);
        validate(j);if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法保存布局");CaptureData.atomic(file(id),j);return j;
    }
    JSONObject read(String id)throws Exception{JSONObject j=ModelStore.json(file(id));if(!id.equals(j.getString("id")))throw new IOException("布局身份不一致");validate(j);return j;}
    ArrayList<JSONObject> list()throws Exception{ArrayList<JSONObject> all=new ArrayList<>();File[] files=root.listFiles((d,n)->n.matches("[a-f0-9]{32}\\.json"));if(files!=null)for(File f:files)all.add(read(f.getName().substring(0,32)));Collections.sort(all,(a,b)->Long.compare(b.optLong("created_at_ms"),a.optLong("created_at_ms")));return all;}
    synchronized void rename(String id,String title)throws Exception{JSONObject j=read(id);j.put("name",name(title));CaptureData.atomic(file(id),j);}
    synchronized void remove(String id)throws Exception{read(id);if(!file(id).delete())throw new IOException("布局未能删除");}
    boolean references(String model)throws Exception{for(JSONObject j:list()){JSONArray items=j.getJSONArray("items");for(int i=0;i<items.length();i++)if(model.equals(items.getJSONObject(i).getString("model_id")))return true;}return false;}
}
