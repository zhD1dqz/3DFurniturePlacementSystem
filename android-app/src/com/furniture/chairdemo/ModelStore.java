package com.furniture.chairdemo;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Immutable private model library; publish only a completely validated model. */
public final class ModelStore {
    public static final String BUILTIN="builtin";
    private static boolean validId(String id){return BUILTIN.equals(id)||(id!=null&&id.matches("[a-f0-9]{64}"));}
    private static File folder(Context context,String id)throws IOException{
        if(!validId(id)||BUILTIN.equals(id))throw new IOException("模型编号无效");
        return new File(new File(context.getFilesDir(),"models"),id);
    }
    static String text(InputStream input)throws IOException{
        try(InputStream in=input){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>131072)throw new IOException("模型信息过大");out.write(b,0,n);}return out.toString("UTF-8");}
    }
    static JSONObject json(File file)throws Exception{return new JSONObject(text(new FileInputStream(file)));}
    static void write(File file,JSONObject value)throws Exception{try(OutputStream out=new FileOutputStream(file)){out.write(value.toString(2).getBytes("UTF-8"));}}
    public static String referenceCaptureId(Context context)throws Exception{
        return new JSONObject(text(context.getAssets().open("capture-rules/capture-rules.json"))).getString("capture_id");
    }
    public static String active(Context context){String id=context.getSharedPreferences("model-library",0).getString("active_id",BUILTIN);return LibraryStore.modelDeleted(context,id)?BUILTIN:id;}
    public static void activate(Context context,String id)throws Exception{
        if(LibraryStore.modelDeleted(context,id))throw new IOException("此模型已删除");
        metadata(context,id);context.getSharedPreferences("model-library",0).edit().putString("active_id",id).commit();
    }
    public static JSONObject dimensions(Context context,String id)throws Exception{
        return BUILTIN.equals(id)?new JSONObject(text(context.getAssets().open("dimensions.json"))):json(new File(folder(context,id),"dimensions.json"));
    }
    public static JSONObject metadata(Context context,String id)throws Exception{
        if(BUILTIN.equals(id)){
            JSONObject value=new JSONObject();value.put("id",BUILTIN);value.put("name","原椅子 · 已验证");value.put("default_height_m",1.0);
            try{value.put("capture_id",referenceCaptureId(context));}catch(Exception ignored){}
            return value;
        }
        File root=folder(context,id);JSONObject value=json(new File(root,"model.json"));
        if(!id.equals(value.getString("id"))||value.getInt("schema_version")!=1)throw new IOException("保存的模型信息无效");
        File packed=new File(root,"model.bin");
        if(!PlyModelImporter.sha(packed).equals(value.getString("packed_sha256")))throw new IOException("保存的模型不完整");
        JSONObject dims=dimensions(context,id);for(String key:new String[]{"width_m","depth_m","height_m"}){double d=dims.getDouble(key);if(!Double.isFinite(d)||d<.01||d>100)throw new IOException("模型尺寸信息无效");}
        try(InputStream check=new FileInputStream(packed)){PackedModel parsed=PackedModel.read(check);if(parsed.count!=value.getInt("gaussians"))throw new IOException("模型数量信息不一致");}
        value.put("name",LibraryNames.furniture(context,value));
        return value;
    }
    static InputStream openPacked(Context context,String id)throws Exception{
        return BUILTIN.equals(id)?context.getAssets().open("chair.bin"):new FileInputStream(new File(folder(context,id),"model.bin"));
    }
    static ArrayList<JSONObject> list(Context context){
        ArrayList<JSONObject> result=new ArrayList<>();
        File[] directories=new File(context.getFilesDir(),"models").listFiles();
        if(directories!=null)for(File directory:directories)if(directory.isDirectory()&&validId(directory.getName())&&!LibraryStore.modelDeleted(context,directory.getName())){
            try{result.add(metadata(context,directory.getName()));}catch(Exception error){android.util.Log.w("ChairDemo","Stored model unavailable: "+directory.getName(),error);}
        }
        Collections.sort(result,(a,b)->Long.compare(b.optLong("created_at_ms"),a.optLong("created_at_ms")));
        return result;
    }
    public static String importGenerated(Context context,File source,float heightHint)throws Exception{
        String privateRoot=context.getFilesDir().getCanonicalPath()+File.separator;
        if(!source.getCanonicalPath().startsWith(privateRoot))throw new IOException("生成结果不在本机任务目录");
        JSONObject completion=json(new File(source.getParentFile(),"result.json"));
        if(!"completed".equals(completion.optString("phase"))||!source.getName().equals(completion.getString("model")))throw new IOException("模型尚未生成完成");
        JSONObject rules=new JSONObject(text(context.getAssets().open("capture-rules/capture-rules.json")));
        JSONObject capture=json(new File(source.getParentFile(),"capture-source.json"));
        if(!rules.getString("capture_id").equals(capture.getString("capture_id")))throw new IOException("当前标注与拍摄数据不匹配");
        String id=PlyModelImporter.sha(source);File models=new File(context.getFilesDir(),"models");models.mkdirs();
        File target=folder(context,id);if(target.exists()){metadata(context,id);LibraryStore.restoreModel(context,id);return id;}
        File staging=new File(models,".pending-"+UUID.randomUUID());if(!staging.mkdir())throw new IOException("无法保存新模型，请检查存储空间");
        JSONObject report=PlyModelImporter.convert(context,source,new File(staging,"model.bin"),rules);
        JSONObject dims=new JSONObject(rules.getJSONObject("dimensions").toString());dims.put("asset_id",id);dims.put("calibration_version",3);
        if(!Float.isFinite(heightHint)||heightHint<.05f||heightHint>10)heightHint=1;
        dims.put("default_height_m",heightHint);dims.put("source","Reference calibration for the same capture; confirm physical dimensions");
        write(new File(staging,"dimensions.json"),dims);
        report.put("schema_version",1);report.put("id",id);report.put("source_ply_sha256",id);report.put("capture_id",rules.getString("capture_id"));
        report.put("name","椅子 · 手机生成 "+new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date()));report.put("created_at_ms",System.currentTimeMillis());
        report.put("default_height_m",heightHint);report.put("packed_sha256",PlyModelImporter.sha(new File(staging,"model.bin")));
        report.put("crop_method","Capture-specific existing manual silhouettes; not automatic segmentation");
        report.put("source_model_path",source.getAbsolutePath());
        write(new File(staging,"model.json"),report);
        if(!PlyModelImporter.sha(source).equals(id))throw new IOException("模型在整理过程中发生变化");
        if(!staging.renameTo(target))throw new IOException("模型保存未完成，请重试");
        metadata(context,id);
        LibraryStore.restoreModel(context,id);
        android.util.Log.i("ChairDemo","MODEL_IMPORTED id="+id+" gaussians="+report.getInt("gaussians"));
        return id;
    }
}
