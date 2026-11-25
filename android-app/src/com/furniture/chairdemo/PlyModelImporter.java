package com.furniture.chairdemo;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** This capture's manual silhouette crop and full Gaussian coordinate transform. */
final class PlyModelImporter {
    static final class View {
        int width,height;byte[] mask;double[][] rotation;double[] translation,intrinsics;
        View(Context context,JSONObject json)throws Exception{
            rotation=matrix(json.getJSONArray("rotation_rows"));translation=vector(json.getJSONArray("translation"));intrinsics=vector(json.getJSONArray("intrinsics"));
            try(InputStream input=new GZIPInputStream(context.getAssets().open("capture-rules/"+json.getString("file")))){
                byte[] header=new byte[8];fully(input,header);ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);width=b.getInt();height=b.getInt();
                if(width<1||height<1||width>4096||height>4096)throw new IOException("提取标注尺寸无效");mask=new byte[width*height];fully(input,mask);
            }
        }
        // -1=unobserved, 0=background, 1=foreground, matching desktop extraction.
        int classify(double x,double y,double z){
            double px=rotation[0][0]*x+rotation[0][1]*y+rotation[0][2]*z+translation[0];
            double py=rotation[1][0]*x+rotation[1][1]*y+rotation[1][2]*z+translation[1];
            double pz=rotation[2][0]*x+rotation[2][1]*y+rotation[2][2]*z+translation[2];
            if(pz<=.001)return -1;
            double u=px/pz*intrinsics[0]+intrinsics[2],v=py/pz*intrinsics[1]+intrinsics[3];
            if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||v<0||u>=width||v>=height)return -1;
            return mask[(int)v*width+(int)u]==0?0:1;
        }
    }
    static double[] vector(JSONArray a)throws JSONException{double[] b=new double[a.length()];for(int i=0;i<b.length;i++)b[i]=a.getDouble(i);return b;}
    static double[][] matrix(JSONArray a)throws JSONException{double[][] b=new double[a.length()][];for(int i=0;i<b.length;i++)b[i]=vector(a.getJSONArray(i));return b;}
    static void fully(InputStream input,byte[] bytes)throws IOException{int offset=0,n;while(offset<bytes.length){n=input.read(bytes,offset,bytes.length-offset);if(n<0)throw new EOFException("模型数据不完整");offset+=n;}}
    static String line(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=input.read())!=-1&&c!='\n'){if(out.size()>2048)throw new IOException("模型文件头异常");out.write(c);}
        if(c<0)throw new EOFException("模型文件头不完整");return out.toString("US-ASCII").trim();
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    static String sha(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}return hex(hash.digest());}
    static JSONObject convert(Context context,File source,File destination,JSONObject rules)throws Exception{
        ArrayList<View> views=new ArrayList<>();JSONArray viewJson=rules.getJSONArray("views");for(int i=0;i<viewJson.length();i++)views.add(new View(context,viewJson.getJSONObject(i)));
        double[][] rotation=matrix(rules.getJSONArray("rotation_rows"));double scale=rules.getDouble("uniform_scale");double[] translation=vector(rules.getJSONArray("translation_m"));
        Map<String,Integer> offsets=new HashMap<>();int count=-1,properties=0;ArrayList<float[]> retained=new ArrayList<>();int stabilized=0;
        MessageDigest indexHash=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new BufferedInputStream(new FileInputStream(source))){
            if(!line(input).equals("ply")||!line(input).equals("format binary_little_endian 1.0"))throw new IOException("暂不支持这个模型格式");
            boolean vertex=false;int headerLines=0;
            while(true){String value=line(input);if(++headerLines>256)throw new IOException("模型字段过多");if(value.equals("end_header"))break;
                String[] parts=value.split("\\s+");
                if(parts[0].equals("element")){vertex=parts[1].equals("vertex");if(vertex)count=Integer.parseInt(parts[2]);else if(Integer.parseInt(parts[2])!=0)throw new IOException("模型包含不支持的数据");}
                if(parts[0].equals("property")&&vertex){if(parts.length!=3||!parts[1].equals("float")||offsets.containsKey(parts[2]))throw new IOException("模型字段不兼容");offsets.put(parts[2],properties++);}
            }
            if(count<1||count>100000||properties>128)throw new IOException("模型规模不受支持");
            ArrayList<String> required=new ArrayList<>(Arrays.asList("x","y","z","opacity"));
            for(int i=0;i<3;i++){required.add("scale_"+i);required.add("f_dc_"+i);}for(int i=0;i<4;i++)required.add("rot_"+i);for(int i=0;i<24;i++)required.add("f_rest_"+i);
            for(String key:required)if(!offsets.containsKey(key))throw new IOException("缺少高斯模型字段："+key);
            byte[] row=new byte[properties*4];float[] values=new float[properties];
            for(int index=0;index<count;index++){
                fully(input,row);ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
                for(float f:values)if(!Float.isFinite(f))throw new IOException("模型有非正常参数，请重新生成");
                double x=values[offsets.get("x")],y=values[offsets.get("y")],z=values[offsets.get("z")];int inside=0,outside=0;
                for(View view:views){int classification=view.classify(x,y,z);if(classification==1)inside++;else if(classification==0)outside++;}
                if(!rules.optBoolean("full_scene",false)&&(inside<3||outside!=0))continue;
                float[] packed=new float[37];double[] point={x,y,z};for(int a=0;a<3;a++){double v=translation[a];for(int b=0;b<3;b++)v+=scale*rotation[a][b]*point[b];packed[a]=(float)v;}
                double[] q=new double[4];double norm=0;for(int a=0;a<4;a++){q[a]=values[offsets.get("rot_"+a)];norm+=q[a]*q[a];}if(norm<1e-16)throw new IOException("模型旋转参数无效");norm=Math.sqrt(norm);for(int a=0;a<4;a++)q[a]/=norm;
                double w=q[0],qx=q[1],qy=q[2],qz=q[3];
                double[][] orient={{1-2*(qy*qy+qz*qz),2*(qx*qy-qz*w),2*(qx*qz+qy*w)},{2*(qx*qy+qz*w),1-2*(qx*qx+qz*qz),2*(qy*qz-qx*w)},{2*(qx*qz-qy*w),2*(qy*qz+qx*w),1-2*(qx*qx+qy*qy)}};
                double[][] axes=new double[3][3],cov=new double[3][3];
                for(int a=0;a<3;a++)for(int b=0;b<3;b++){for(int c=0;c<3;c++)axes[a][b]+=rotation[a][c]*orient[c][b];axes[a][b]*=scale*Math.exp(values[offsets.get("scale_"+b)]);}
                for(int a=0;a<3;a++)for(int b=0;b<3;b++)for(int c=0;c<3;c++)cov[a][b]+=axes[a][c]*axes[b][c];
                packed[3]=(float)cov[0][0];packed[4]=(float)cov[1][1];packed[5]=(float)cov[2][2];packed[6]=(float)cov[0][1];packed[7]=(float)cov[0][2];packed[8]=(float)cov[1][2];
                if(!PackedModel.positive(packed,0)){double jitter=Math.max(1e-10,(cov[0][0]+cov[1][1]+cov[2][2])*1e-6);for(int a=3;a<=5;a++)packed[a]+=jitter;stabilized++;}
                for(int a=0;a<3;a++)packed[9+a]=values[offsets.get("f_dc_"+a)];double opacity=values[offsets.get("opacity")];packed[12]=(float)(opacity>=0?1/(1+Math.exp(-opacity)):Math.exp(opacity)/(1+Math.exp(opacity)));
                for(int j=0;j<8;j++)for(int c=0;c<3;c++)packed[13+j*3+c]=values[offsets.get("f_rest_"+(c*8+j))];
                for(float f:packed)if(!Float.isFinite(f))throw new IOException("模型转换后数值无效");if(!PackedModel.positive(packed,0))throw new IOException("模型形状无法稳定显示");
                retained.add(packed);indexHash.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index).array());
            }
        }
        if(retained.size()<100)throw new IOException("家具提取结果过少，需要检查素材或标注");
        ByteBuffer packed=ByteBuffer.allocate(44+retained.size()*37*4).order(ByteOrder.LITTLE_ENDIAN);packed.putInt(0x31534746).putInt(retained.size());
        // Column-major inverse rotation = transpose of source-to-model rotation.
        for(int column=0;column<3;column++)for(int row=0;row<3;row++)packed.putFloat((float)rotation[column][row]);
        for(float[] row:retained)for(float v:row)packed.putFloat(v);
        try(OutputStream out=new FileOutputStream(destination)){out.write(packed.array());}
        try(InputStream check=new FileInputStream(destination)){PackedModel.read(check);}
        JSONObject report=new JSONObject();report.put("source_gaussians",count);report.put("gaussians",retained.size());report.put("covariances_stabilized",stabilized);report.put("retained_source_indices_sha256",hex(indexHash.digest()));return report;
    }
}
