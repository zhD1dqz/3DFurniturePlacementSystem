package com.furniture.chairdemo;
import android.content.Context;
import android.graphics.*;
import android.opengl.Matrix;
import java.io.*;
import java.util.*;
import org.json.*;

/** Phone-only adapter. COLMAP text is a file format here, not a desktop COLMAP run. */
public final class CaptureDataset {
    static double[] quaternion(float[] m){
        double[] q=new double[4];double t=m[0]+m[5]+m[10];
        if(t>0){double s=Math.sqrt(t+1)*2;q[0]=s/4;q[1]=(m[6]-m[9])/s;q[2]=(m[8]-m[2])/s;q[3]=(m[1]-m[4])/s;}
        else{int i=m[0]>m[5]?(m[0]>m[10]?0:2):(m[5]>m[10]?1:2),j=(i+1)%3,k=(i+2)%3;double s=Math.sqrt(1+m[i*5]-m[j*5]-m[k*5])*2;q[i+1]=s/4;q[0]=(m[j*4+k]-m[k*4+j])/s;q[j+1]=(m[j*4+i]+m[i*4+j])/s;q[k+1]=(m[k*4+i]+m[i*4+k])/s;}
        double n=0;for(double v:q)n+=v*v;n=Math.sqrt(n);for(int i=0;i<4;i++)q[i]/=n;return q;
    }
    static void copy(File source,File target)throws Exception{try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    public static File prepare(Context context,String id)throws Exception{
        if(id==null||!id.matches("scan-\\d+-[a-f0-9]{8}"))throw new IOException("采集编号无效");
        File source=new File(context.getFilesDir(),"captures/"+id);JSONObject manifest=ModelStore.json(new File(source,"capture.json"));
        if(!"completed".equals(manifest.optString("state")))throw new IOException("请先结束并保存采集");
        File[] frames=source.listFiles((d,n)->n.matches("frame-\\d{4}\\.json"));if(frames==null||frames.length<20)throw new IOException("至少需要20张有效画面");Arrays.sort(frames);
        File root=new File(context.getFilesDir(),"training-inputs");root.mkdirs();File target=new File(root,id);
        // Regenerate in a fresh folder; only publish after the input is complete.
        File staging=new File(root,id+"-"+UUID.randomUUID());new File(staging,"images").mkdirs();new File(staging,"sparse/0").mkdirs();
        LinkedHashMap<String,String> seeds=new LinkedHashMap<>();long previousFrame=0,previousImage=0;int index=0;
        try(PrintWriter cameras=new PrintWriter(new File(staging,"sparse/0/cameras.txt"),"UTF-8");PrintWriter images=new PrintWriter(new File(staging,"sparse/0/images.txt"),"UTF-8")){
            for(File file:frames){JSONObject f=ModelStore.json(file);String name=f.getString("image");if(!name.matches("frame-\\d{4}\\.jpg"))throw new IOException("图像名称无效");File image=new File(source,name);
                if(!"TRACKING".equals(f.getString("tracking_state"))||!PlyModelImporter.sha(image).equals(f.getString("image_sha256")))throw new IOException("图像或相机数据不完整");
                long ft=f.getLong("frame_timestamp_ns"),it=f.getLong("image_timestamp_ns");if(!CaptureData.freshImage(ft,previousFrame,it,previousImage))throw new IOException("采集时间顺序异常");previousFrame=ft;previousImage=it;
                JSONArray c=f.getJSONArray("camera_to_world_cv_column_major");float[] c2w=new float[16],w2c=new float[16];for(int i=0;i<16;i++)c2w[i]=(float)c.getDouble(i);CaptureData.array(c2w);if(!Matrix.invertM(w2c,0,c2w,0))throw new IOException("相机位姿不可逆");double[] q=quaternion(w2c);
                JSONArray focal=f.getJSONArray("focal_length_px"),center=f.getJSONArray("principal_point_px");int w=f.getInt("width"),h=f.getInt("height");double fx=focal.getDouble(0),fy=focal.getDouble(1),cx=center.getDouble(0),cy=center.getDouble(1);if(fx<=0||fy<=0)throw new IOException("焦距无效");
                int n=++index;cameras.printf(Locale.US,"%d PINHOLE %d %d %.12g %.12g %.12g %.12g%n",n,w,h,fx,fy,cx,cy);
                images.printf(Locale.US,"%d %.12g %.12g %.12g %.12g %.12g %.12g %.12g %d %s%n%n",n,q[0],q[1],q[2],q[3],w2c[12],w2c[13],w2c[14],n,name);
                copy(image,new File(staging,"images/"+name));Bitmap bitmap=BitmapFactory.decodeFile(image.getAbsolutePath());if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h)throw new IOException("照片尺寸无效");
                try{JSONArray points=f.optJSONArray("point_cloud_world_xyzc");if(points!=null)for(int i=0;i+3<points.length();i+=4){double x=points.getDouble(i),y=points.getDouble(i+1),z=points.getDouble(i+2),confidence=points.getDouble(i+3);if(!Double.isFinite(x+y+z+confidence)||confidence<.3)continue;
                    double px=w2c[0]*x+w2c[4]*y+w2c[8]*z+w2c[12],py=w2c[1]*x+w2c[5]*y+w2c[9]*z+w2c[13],pz=w2c[2]*x+w2c[6]*y+w2c[10]*z+w2c[14];if(pz<.1)continue;double u=fx*px/pz+cx,v=fy*py/pz+cy;if(u<0||v<0||u>=w||v>=h)continue;
                    String key=Math.round(x/.01)+","+Math.round(y/.01)+","+Math.round(z/.01);if(seeds.containsKey(key))continue;int rgb=bitmap.getPixel((int)u,(int)v);seeds.put(key,String.format(Locale.US,"%.12g %.12g %.12g %d %d %d 0",x,y,z,Color.red(rgb),Color.green(rgb),Color.blue(rgb)));
                }}finally{bitmap.recycle();}
            }
            if(cameras.checkError()||images.checkError())throw new IOException("无法写入训练相机");
        }
        if(seeds.size()<32)throw new IOException("有效空间点不足，请增加有纹理视角");
        try(PrintWriter points=new PrintWriter(new File(staging,"sparse/0/points3D.txt"),"UTF-8")){int n=0;for(String value:seeds.values())points.println((++n)+" "+value);if(points.checkError())throw new IOException("无法写入初始化点");}
        CaptureData.atomic(new File(staging,"input-report.json"),new JSONObject().put("capture_id",id).put("frames",index).put("seed_points",seeds.size()).put("pose_source","ARCore physical camera; no desktop pose estimation").put("crop_applied",false));
        // Versioned immutable input folders allow safe retries without deleting data.
        return staging;
    }
}
