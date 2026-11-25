package com.furniture.chairdemo;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** Deletion registry plus reference-aware cleanup of private library files. */
final class LibraryStore {
    final File root;
    LibraryStore(File files){root=files;}
    JSONObject registry()throws Exception{File f=new File(root,"library-deletions.json");return f.exists()?ModelStore.json(f):new JSONObject();}
    static HashSet<String> values(JSONObject j,String key){HashSet<String> out=new HashSet<>();JSONArray a=j.optJSONArray(key);if(a!=null)for(int i=0;i<a.length();i++)out.add(a.optString(i));return out;}
    void save(JSONObject j)throws Exception{CaptureData.atomic(new File(root,"library-deletions.json"),j);}
    static boolean modelDeleted(Context c,String id){return new LibraryStore(c.getFilesDir()).deleted("models",id);}
    static boolean taskDeleted(Context c,File task){return new LibraryStore(c.getFilesDir()).deleted("tasks",task.getName());}
    boolean deleted(String kind,String id){try{return values(registry(),kind).contains(id);}catch(Exception error){throw new IllegalStateException("无法读取删除记录",error);}}
    static synchronized void restoreModel(Context c,String id)throws Exception{LibraryStore store=new LibraryStore(c.getFilesDir());JSONObject j=store.registry();HashSet<String> ids=values(j,"models");if(ids.remove(id)){j.put("models",new JSONArray(ids));store.save(j);}}
    static synchronized void deleteModel(Context c,String id)throws Exception{
        boolean active=id.equals(ModelStore.active(c));LibraryStore store=new LibraryStore(c.getFilesDir());store.removeModel(id);
        if(active)ModelStore.activate(c,ModelStore.BUILTIN);
    }
    static synchronized void deleteScene(Context c,File task)throws Exception{new LibraryStore(c.getFilesDir()).removeScene(task.getName());}
    void mark(JSONObject j,String kind,String id)throws Exception{HashSet<String> ids=values(j,kind);ids.add(id);j.put(kind,new JSONArray(ids));}
    void removeModel(String id)throws Exception{
        if(id==null||!id.matches("[a-f0-9]{64}"))throw new IOException("不能删除示例或无效模型");
        if(new RoomLayoutStore(root).references(id))throw new IOException("此家具被已保存的房间布局使用，请先删除相关布局");
        JSONObject j=registry();mark(j,"models",id);save(j);collect();
    }
    void removeScene(String name)throws Exception{
        if(name==null||!name.matches("probe-\\d+-1200"))throw new IOException("场景编号无效");
        File task=new File(root,name);JSONObject result=ModelStore.json(new File(task,"integration-result.json"));
        if(!"completed".equals(result.optString("phase")))throw new IOException("只能删除已完成场景");
        String model=result.getString("model");if(!model.matches("generated_\\d+\\.ply"))throw new IOException("场景文件名无效");
        JSONObject j=registry();mark(j,"tasks",name);mark(j,"scenes",PlyModelImporter.sha(new File(task,model)));save(j);collect();
    }
    void collect()throws Exception{
        JSONObject j=registry();Set<String> removedModels=values(j,"models"),removedTasks=values(j,"tasks");
        Set<String> neededModels=new HashSet<>(),neededScenes=new HashSet<>();ArrayDeque<String> queue=new ArrayDeque<>();
        File models=new File(root,"models");File[] folders=models.listFiles();
        if(folders!=null)for(File f:folders)if(f.getName().matches("[a-f0-9]{64}")&&!removedModels.contains(f.getName()))queue.add(f.getName());
        while(!queue.isEmpty()){
            String id=queue.remove();if(!neededModels.add(id))continue;
            JSONObject meta=ModelStore.json(new File(models,id+"/model.json"));String parent=meta.optString("calibration_source_id"),scene=meta.optString("source_scene_id");
            if(parent.matches("[a-f0-9]{64}"))queue.add(parent);if(scene.matches("[a-f0-9]{64}"))neededScenes.add(scene);
        }
        File[] tasks=root.listFiles((d,n)->n.matches("probe-\\d+-1200"));
        if(tasks!=null)for(File task:tasks)if(!removedTasks.contains(task.getName()))try{
            JSONObject result=ModelStore.json(new File(task,"integration-result.json"));String name=result.optString("model");
            if("completed".equals(result.optString("phase"))&&name.matches("generated_\\d+\\.ply"))neededScenes.add(PlyModelImporter.sha(new File(task,name)));
        }catch(Exception ignored){}
        for(String name:removedTasks)if(name.matches("probe-\\d+-1200"))erase(new File(root,name));
        for(String id:removedModels)if(id.matches("[a-f0-9]{64}")&&!neededModels.contains(id))erase(new File(models,id));
        for(String id:values(j,"scenes"))if(id.matches("[a-f0-9]{64}")&&!neededScenes.contains(id))erase(new File(root,"scene-models/"+id));
    }
    void erase(File selected)throws IOException{
        File base=root.getCanonicalFile(),target=selected.getCanonicalFile();
        if(target.equals(base)||!target.getPath().startsWith(base.getPath()+File.separator))throw new IOException("删除路径越界");
        String absolute=root.getAbsolutePath()+File.separator;if(!selected.getAbsolutePath().startsWith(absolute)||!target.equals(new File(base,selected.getAbsolutePath().substring(absolute.length()))))throw new IOException("删除路径包含链接");
        eraseWithin(target,target);
    }
    void eraseWithin(File file,File selected)throws IOException{
        if(!file.exists())return;File actual=file.getCanonicalFile();
        if(!actual.equals(file.getAbsoluteFile()))throw new IOException("删除目录包含链接");
        if(!actual.equals(selected)&&!actual.getPath().startsWith(selected.getPath()+File.separator))throw new IOException("删除目录包含外部链接");
        File[] children=file.listFiles();if(children!=null)for(File child:children)eraseWithin(child,selected);
        if(!file.delete())throw new IOException("部分文件未能删除，请重试");
    }
}
