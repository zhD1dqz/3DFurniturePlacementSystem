package com.furniture.chairdemo;
import android.content.Context;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Uniform metric calibration of a crop. Ground is an editable estimate, not a measured mesh surface. */
final class GroundCalibration {
    static File modelFolder(Context c,String id)throws IOException{if(id==null||!id.matches("[a-f0-9]{64}"))throw new IOException("家具编号无效");return new File(c.getFilesDir(),"models/"+id);}
    static String base(Context c,String id)throws Exception{JSONObject m=ModelStore.metadata(c,id);return "calibrated_furniture".equals(m.optString("kind"))?m.getString("calibration_source_id"):id;}
    static PackedModel model(Context c,String id)throws Exception{try(InputStream in=ModelStore.openPacked(c,id)){return PackedModel.read(in);}}
    static float quantile(float[] a,int n,double fraction){Arrays.sort(a,0,n);return a[(int)Math.round((n-1)*fraction)];}
    static float[] suggest(PackedModel model)throws IOException{
        float[] ground=new float[model.count],top=new float[model.count];int n=0;
        for(int k=0;k<model.rows.length;k+=37){float[] p=model.rows;float max=Math.max(p[k+3],Math.max(p[k+4],p[k+5]));if(p[k+12]<.25||max>.06*.06)continue;
            ground[n]=p[k+1];double extent=Math.sqrt(Math.max(0,2*Math.log(p[k+12]/.1)))*Math.sqrt(p[k+4]);top[n++]=(float)(p[k+1]+extent);
        }
        if(n<32){n=0;for(int k=0;k<model.rows.length;k+=37)if(model.rows[k+12]>=.1){ground[n]=model.rows[k+1];top[n++]=model.rows[k+1];}}
        if(n<10)throw new IOException("可用于接地估计的模型点过少");float g=quantile(ground,n,.02),t=quantile(top,n,.995);if(!Float.isFinite(g+t)||t-g<.05)throw new IOException("家具高度范围异常");return new float[]{g,t};
    }
    static String save(Context c,String requestedId,float ground,float height)throws Exception{
        String sourceId=base(c,requestedId);JSONObject parent=ModelStore.metadata(c,sourceId);if(!"cropped_furniture_pending".equals(parent.optString("kind")))throw new IOException("请先提取家具再校准");
        PackedModel source=model(c,sourceId);float[] estimate=suggest(source);if(!Float.isFinite(ground)||Math.abs(ground-estimate[0])>.251||!Float.isFinite(height)||height<.05||height>10||estimate[1]-ground<.05)throw new IOException("请检查接地面和实测高度");
        float scale=height/(estimate[1]-ground);if(scale<.05||scale>20)throw new IOException("校准比例异常，请核对厘米单位和接地面");
        JSONObject spec=new JSONObject().put("ground_y_source",ground).put("top_reference_y",estimate[1]).put("measured_height_m",height).put("uniform_scale",scale);
        String id=PlyModelImporter.hex(MessageDigest.getInstance("SHA-256").digest(("ground-v1:"+sourceId+":"+spec).getBytes("UTF-8")));File target=modelFolder(c,id);if(target.exists()){ModelStore.metadata(c,id);LibraryStore.restoreModel(c,id);return id;}
        float[] rows=source.rows.clone();int stabilized=0;double maxAddedVariance=0;
        for(int k=0;k<rows.length;k+=37){rows[k]*=scale;rows[k+1]=(rows[k+1]-ground)*scale;rows[k+2]*=scale;for(int i=3;i<9;i++)rows[k+i]*=scale*scale;
            // A valid but very flat Gaussian can lose positive definiteness when
            // its scaled covariance is rounded to float32. Repair only those
            // matrices, keeping positions, SH, opacity and all source rows intact.
            if(!PackedModel.positive(rows,k)){
                double trace=(double)rows[k+3]+rows[k+4]+rows[k+5];if(!Double.isFinite(trace)||trace<=0)throw new IOException("家具形状换算失败，请检查尺寸");
                double jitter=Math.max(1e-12,trace*1e-6),added=0;
                for(int attempt=0;attempt<3&&!PackedModel.positive(rows,k);attempt++){for(int i=3;i<=5;i++)rows[k+i]+=jitter;added+=jitter;jitter*=10;}
                if(!PackedModel.positive(rows,k))throw new IOException("家具形状无法稳定换算，原模型已保留");stabilized++;maxAddedVariance=Math.max(maxAddedVariance,added);
            }
        }
        ByteBuffer bytes=ByteBuffer.allocate(44+rows.length*4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31534746).putInt(source.count);for(float f:source.inverse)bytes.putFloat(f);for(float f:rows)bytes.putFloat(f);
        File pending=new File(c.getFilesDir(),"models/.pending-"+UUID.randomUUID());if(!pending.mkdirs())throw new IOException("无法保存校准副本");File packed=new File(pending,"model.bin");try(OutputStream out=new FileOutputStream(packed)){out.write(bytes.array());}try(InputStream in=new FileInputStream(packed)){PackedModel.read(in);}
        JSONObject previous=ModelStore.dimensions(c,sourceId);JSONObject dims=new JSONObject().put("asset_id",id).put("width_m",previous.getDouble("width_m")*scale).put("depth_m",previous.getDouble("depth_m")*scale).put("height_m",height).put("default_height_m",height).put("calibration_version",4).put("source","User-measured height; uniform scaling; width/depth estimated; adjustable suggested ground");
        ModelStore.write(new File(pending,"dimensions.json"),dims);JSONObject meta=new JSONObject(parent.toString()).put("id",id).put("name","已校准家具 · "+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date())).put("kind","calibrated_furniture").put("calibration_source_id",sourceId).put("calibration",spec).put("measured_height_m",height).put("default_height_m",height).put("ar_ready",true).put("packed_sha256",PlyModelImporter.sha(packed)).put("ground_source","Suggested sparse opaque Gaussian lower centers; user-adjustable; real scene check required").put("covariances_stabilized_after_scale",stabilized).put("max_added_variance_m2",maxAddedVariance).put("created_at_ms",System.currentTimeMillis());ModelStore.write(new File(pending,"model.json"),meta);
        if(!pending.renameTo(target))throw new IOException("校准保存未完成");ModelStore.metadata(c,id);LibraryStore.restoreModel(c,id);return id;
    }
    static String prepareForRoom(Context c,String source,float depth,float width,float height,String mode)throws Exception{
        for(float value:new float[]{depth,width,height})if(!Float.isFinite(value)||value<.05f||value>10)throw new IOException("请先确认家具实际尺寸");
        float[] range=suggest(model(c,source));String id=save(c,source,range[0],height);
        // Confirmation in the normal dimensions dialog is sufficient. Preserve all
        // three values and the user's sizing mode when opening the grounded copy.
        c.getSharedPreferences("model-size-"+id,0).edit().putString("assetId",id).putFloat("depth",depth).putFloat("width",width).putFloat("height",height).putBoolean("dimensionsConfirmed",true).putString("sizeMode",mode).putInt("calibrationVersion",4).putBoolean("calibratedMetadataApplied",true).commit();
        return id;
    }
}
