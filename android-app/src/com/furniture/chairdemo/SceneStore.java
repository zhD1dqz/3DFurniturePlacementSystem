package com.furniture.chairdemo;
import android.content.Context;
import android.opengl.Matrix;
import java.io.*;
import java.util.*;
import org.json.*;

/** Immutable full scenes, separate from calibrated furniture and its dimensions. */
final class SceneStore {
    static File folder(Context c,String id)throws IOException{if(id==null||!id.matches("[a-f0-9]{64}"))throw new IOException("场景编号无效");return new File(c.getFilesDir(),"scene-models/"+id);}
    static JSONObject load(Context c,String id)throws Exception{
        File root=folder(c,id);JSONObject meta=ModelStore.json(new File(root,"scene.json"));if(!id.equals(meta.getString("id"))||!PlyModelImporter.sha(new File(root,"scene.bin")).equals(meta.getString("packed_sha256")))throw new IOException("场景文件不完整");return meta;
    }
    static File latestTask(Context c)throws Exception{
        File[] tasks=c.getFilesDir().listFiles((d,n)->n.matches("probe-\\d+-1200"));if(tasks!=null){Arrays.sort(tasks,(a,b)->b.getName().compareTo(a.getName()));for(File task:tasks)try{if(LibraryStore.taskDeleted(c,task))continue;JSONObject r=ModelStore.json(new File(task,"integration-result.json"));if("completed".equals(r.optString("phase"))&&r.optString("capture_id").matches("scan-\\d+-[a-f0-9]{8}"))return task;}catch(Exception ignored){}}
        throw new IOException("还没有新扫描模型");
    }
    static String prepare(Context c,File task)throws Exception{
        if(LibraryStore.taskDeleted(c,task))throw new IOException("此场景已删除");
        if(!task.getName().matches("probe-\\d+-1200")||!task.getCanonicalFile().getParentFile().equals(c.getFilesDir().getCanonicalFile()))throw new IOException("任务路径无效");
        JSONObject result=ModelStore.json(new File(task,"integration-result.json"));String name=result.getString("model"),capture=result.getString("capture_id");
        if(!"completed".equals(result.optString("phase"))||!name.matches("generated_\\d+\\.ply")||!capture.matches("scan-\\d+-[a-f0-9]{8}"))throw new IOException("模型未生成完成");
        File source=new File(task,name);String id=PlyModelImporter.sha(source);File target=folder(c,id);if(target.exists()){load(c,id);return id;}
        File pending=new File(c.getFilesDir(),"scene-models/.pending-"+UUID.randomUUID());if(!pending.mkdirs())throw new IOException("无法保存场景");
        JSONObject rules=new JSONObject().put("full_scene",true).put("views",new JSONArray()).put("rotation_rows",new JSONArray("[[1,0,0],[0,1,0],[0,0,1]]")).put("uniform_scale",1).put("translation_m",new JSONArray("[0,0,0]"));
        File packed=new File(pending,"scene.bin");JSONObject meta=PlyModelImporter.convert(c,source,packed,rules);PackedModel model;try(InputStream in=new FileInputStream(packed)){model=PackedModel.read(in);}
        float[] low=new float[3],high=new float[3];for(int axis=0;axis<3;axis++){float[] v=new float[model.count];int n=0;for(int i=0;i<model.count;i++)if(model.rows[i*37+12]>=.1f)v[n++]=model.rows[i*37+axis];if(n<10)throw new IOException("可见模型点过少");Arrays.sort(v,0,n);low[axis]=v[(int)((n-1)*.01)];high[axis]=v[(int)((n-1)*.99)];}
        float[] center=new float[3];float radius=0;for(int i=0;i<3;i++){center[i]=(low[i]+high[i])/2;radius+=(high[i]-low[i])*(high[i]-low[i])/4;}radius=(float)Math.sqrt(radius);if(!Float.isFinite(radius)||radius<.02f||radius>100)throw new IOException("场景范围异常");
        float[] focus=center.clone(),eye={center[0],center[1]+radius*.2f,center[2]+radius*1.2f};float[] sum=new float[16],rhs=new float[4];sum[15]=1;boolean first=true;int rays=0;
        File[] frames=new File(c.getFilesDir(),"captures/"+capture).listFiles((d,n)->n.matches("frame-\\d{4}\\.json"));
        if(frames!=null){Arrays.sort(frames);for(File frame:frames)try{
            JSONObject f=ModelStore.json(frame);if(!"TRACKING".equals(f.optString("tracking_state")))continue;double[] p=PlyModelImporter.vector(f.getJSONArray("camera_to_world_gl_column_major"));double[] focal=PlyModelImporter.vector(f.getJSONArray("focal_length_px")),principal=PlyModelImporter.vector(f.getJSONArray("principal_point_px"));
            double x=(f.getInt("width")*.5-principal[0])/focal[0],y=-(f.getInt("height")*.5-principal[1])/focal[1];double[] d=new double[3];double norm=0;for(int a=0;a<3;a++){d[a]=p[a]*x+p[4+a]*y-p[8+a];norm+=d[a]*d[a];}norm=Math.sqrt(norm);for(int a=0;a<3;a++)d[a]/=norm;
            for(int a=0;a<3;a++)for(int b=0;b<3;b++){double value=(a==b?1:0)-d[a]*d[b];sum[b*4+a]+=value;rhs[a]+=value*p[12+b];}
            if(first){for(int a=0;a<3;a++)eye[a]=(float)p[12+a];first=false;}rays++;
        }catch(Exception ignored){}}
        float[] inverse=new float[16],candidate=new float[4];if(rays>=3&&Matrix.invertM(inverse,0,sum,0)){Matrix.multiplyMV(candidate,0,inverse,0,rhs,0);double distance=0;for(int a=0;a<3;a++)distance+=(candidate[a]-center[a])*(candidate[a]-center[a]);if(Double.isFinite(distance)&&distance<radius*radius*4)System.arraycopy(candidate,0,focus,0,3);}
        meta.put("id",id).put("packed_sha256",PlyModelImporter.sha(packed)).put("capture_id",capture).put("source_task",task.getName()).put("source_ply_sha256",id).put("kind","full_scene_uncropped").put("center",CaptureData.array(center)).put("radius",radius).put("focus",CaptureData.array(focus)).put("initial_eye",CaptureData.array(eye)).put("focus_method","Estimated intersection of capture center rays; not furniture segmentation");
        ModelStore.write(new File(pending,"scene.json"),meta);if(!PlyModelImporter.sha(source).equals(id)||!pending.renameTo(target))throw new IOException("保存场景失败");load(c,id);return id;
    }
}
