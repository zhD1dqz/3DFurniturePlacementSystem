package com.furniture.chairdemo;

import java.io.*;
import java.nio.*;

/** Validated renderer data, independent of where the model was stored. */
final class PackedModel {
    final int count;
    final float[] inverse,rows;
    private PackedModel(int count,float[] inverse,float[] rows){this.count=count;this.inverse=inverse;this.rows=rows;}
    static PackedModel read(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[16384];int n;
        while((n=input.read(block))!=-1){if(out.size()+n>15000044)throw new IOException("模型文件过大");out.write(block,0,n);}
        ByteBuffer bytes=ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        if(bytes.remaining()<44||bytes.getInt()!=0x31534746)throw new IOException("模型格式不正确");
        int count=bytes.getInt();if(count<1||count>100000||bytes.remaining()!=(9+count*37)*4)throw new IOException("模型不完整");
        float[] inverse=new float[9],rows=new float[count*37];bytes.asFloatBuffer().get(inverse);bytes.position(bytes.position()+36);bytes.asFloatBuffer().get(rows);
        for(float v:inverse)if(!Float.isFinite(v))throw new IOException("模型方向无效");
        for(int i=0;i<3;i++)for(int j=0;j<3;j++){double dot=0;for(int k=0;k<3;k++)dot+=inverse[i*3+k]*inverse[j*3+k];if(Math.abs(dot-(i==j?1:0))>.001)throw new IOException("模型方向未校准");}
        for(int i=0;i<count;i++){
            int k=i*37;for(int j=0;j<37;j++)if(!Float.isFinite(rows[k+j]))throw new IOException("模型包含无效数值");
            if(rows[k+12]<0||rows[k+12]>1||!positive(rows,k))throw new IOException("模型形状参数无效");
        }
        return new PackedModel(count,inverse,rows);
    }
    static boolean positive(float[] p,int k){
        double a=p[k+3],b=p[k+4],c=p[k+5],d=p[k+6],e=p[k+7],f=p[k+8];
        return a>0&&b>0&&c>0&&a*b-d*d>0&&a*b*c+2*d*e*f-a*f*f-b*e*e-c*d*d>0;
    }
}
