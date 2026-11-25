package com.furniture.chairdemo;
import android.content.Context;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Explicit user crop; never modifies source scene or pretends to be semantic segmentation. */
final class CropStore {
    static String save(Context c,String sceneId,CropBox box)throws Exception{
        return save(c,sceneId,box,null);
    }
    static String save(Context c,String sceneId,CropBox box,String name)throws Exception{
        if(name!=null)name=LibraryNames.validate(name,false);
        JSONObject scene=SceneStore.load(c,sceneId);PackedModel source;try(InputStream in=new FileInputStream(new File(SceneStore.folder(c,sceneId),"scene.bin"))){source=PackedModel.read(in);}
        JSONObject spec=box.json();String id=PlyModelImporter.hex(MessageDigest.getInstance("SHA-256").digest(("crop-v1:"+sceneId+":"+spec.toString()).getBytes("UTF-8")));
        File models=new File(c.getFilesDir(),"models");models.mkdirs();File target=new File(models,id);if(target.exists())return publishName(c,id,name);
        float[] rows=CropBox.select(source.rows,box);int count=rows.length/37;if(count<100)throw new IOException("范围内模型点过少，请扩大范围再保存");
        double co=Math.cos(box.yaw),si=Math.sin(box.yaw);double[][] rotation={{co,0,si},{0,1,0},{-si,0,co}};
        float minX=Float.POSITIVE_INFINITY,maxX=-minX,minZ=minX,maxZ=-minX,maxY=0;
        for(int k=0;k<rows.length;k+=37){double dx=rows[k]-box.x,dz=rows[k+2]-box.z;rows[k]=(float)(co*dx+si*dz);rows[k+1]-=box.y-box.height/2;rows[k+2]=(float)(-si*dx+co*dz);
            minX=Math.min(minX,rows[k]);maxX=Math.max(maxX,rows[k]);minZ=Math.min(minZ,rows[k+2]);maxZ=Math.max(maxZ,rows[k+2]);maxY=Math.max(maxY,rows[k+1]);
            double[][] cov={{rows[k+3],rows[k+6],rows[k+7]},{rows[k+6],rows[k+4],rows[k+8]},{rows[k+7],rows[k+8],rows[k+5]}},transformed=new double[3][3];
            for(int a=0;a<3;a++)for(int b=0;b<3;b++)for(int i=0;i<3;i++)for(int j=0;j<3;j++)transformed[a][b]+=rotation[a][i]*cov[i][j]*rotation[b][j];
            rows[k+3]=(float)transformed[0][0];rows[k+4]=(float)transformed[1][1];rows[k+5]=(float)transformed[2][2];rows[k+6]=(float)transformed[0][1];rows[k+7]=(float)transformed[0][2];rows[k+8]=(float)transformed[1][2];
            if(!PackedModel.positive(rows,k)){float jitter=Math.max(1e-10f,(rows[k+3]+rows[k+4]+rows[k+5])*1e-6f);rows[k+3]+=jitter;rows[k+4]+=jitter;rows[k+5]+=jitter;}
        }
        for(int k=0;k<rows.length;k+=37){rows[k]-=(minX+maxX)/2;rows[k+2]-=(minZ+maxZ)/2;}
        float w=maxX-minX,d=maxZ-minZ;if(w<.01||d<.01||maxY<.01)throw new IOException("裁剪结果范围异常");
        ByteBuffer data=ByteBuffer.allocate(44+rows.length*4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31534746).putInt(count);
        // SH needs source scene directions = source.inverse * transpose(rotation).
        for(int col=0;col<3;col++)for(int row=0;row<3;row++){double value=0;for(int i=0;i<3;i++)value+=source.inverse[i*3+row]*rotation[col][i];data.putFloat((float)value);}
        for(float f:rows)data.putFloat(f);File pending=new File(models,".pending-"+UUID.randomUUID());if(!pending.mkdir())throw new IOException("无法保存裁剪");File packed=new File(pending,"model.bin");try(OutputStream out=new FileOutputStream(packed)){out.write(data.array());}try(InputStream in=new FileInputStream(packed)){PackedModel.read(in);}
        ModelStore.write(new File(pending,"dimensions.json"),new JSONObject().put("width_m",w).put("depth_m",d).put("height_m",maxY).put("default_height_m",maxY).put("asset_id",id).put("calibration_version",3).put("source","Crop bounds estimate; not measured furniture dimensions"));
        ModelStore.write(new File(pending,"model.json"),new JSONObject().put("schema_version",1).put("id",id).put("name","提取家具 · "+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date())).put("kind","cropped_furniture_pending").put("source_scene_id",sceneId).put("capture_id",scene.getString("capture_id")).put("gaussians",count).put("packed_sha256",PlyModelImporter.sha(packed)).put("default_height_m",maxY).put("crop",spec).put("crop_method","User-adjusted 3D box; Gaussian centers retained; not automatic segmentation").put("created_at_ms",System.currentTimeMillis()).put("ar_ready",false));
        if(!pending.renameTo(target))throw new IOException("保存未完成，请重试");return publishName(c,id,name);
    }
    private static String publishName(Context c,String id,String name)throws Exception{
        JSONObject model=ModelStore.metadata(c,id);LibraryStore.restoreModel(c,id);
        if(name!=null)LibraryNames.renameFurniture(c,model,name);
        return id;
    }
}
