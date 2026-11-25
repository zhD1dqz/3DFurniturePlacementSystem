package com.furniture.chairdemo;
import android.app.*;
import android.os.Bundle;
import android.graphics.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Shows actual phone evaluation renders; does not present an extracted furniture model. */
public class CaptureResultsActivity extends Activity {
    File selectedTask;Bitmap bitmap;ImageView image;File[] pictures;int index;TextView caption;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff131a20);
        TextView title=new TextView(this);title.setText("新扫描生成结果 · 含背景");title.setTextSize(23);title.setPadding(24,16,24,12);root.addView(title);
        TextView info=new TextView(this);info.setPadding(24,0,24,12);root.addView(info);
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));caption=new TextView(this);caption.setPadding(24,8,24,8);root.addView(caption);
        Button three=new Button(this);three.setText("打开三维高斯模型");three.setOnClickListener(v->{if(selectedTask!=null)SceneViewerActivity.open(this,selectedTask);});root.addView(three);
        Button next=new Button(this);next.setText("下一个视角");next.setOnClickListener(v->{if(pictures!=null){index=(index+1)%pictures.length;showImage();}});root.addView(next);
        Button rotate=new Button(this);rotate.setText("旋转画面");rotate.setOnClickListener(v->image.setRotation((image.getRotation()+90)%360));root.addView(rotate);
        Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->finish());root.addView(back);setContentView(root);WindowLayout.safe(root);
        try{
            File[] tasks=getFilesDir().listFiles((d,n)->n.matches("probe-\\d+-1200"));if(tasks==null)throw new IOException("还没有新扫描生成结果");Arrays.sort(tasks,(a,b)->b.getName().compareTo(a.getName()));File selected=null;JSONObject result=null;
            for(File task:tasks)try{JSONObject r=ModelStore.json(new File(task,"integration-result.json"));if("completed".equals(r.optString("phase"))&&r.optString("capture_id").startsWith("scan-")){selected=task;result=r;break;}}catch(Exception ignored){}
            if(selected==null)throw new IOException("还没有新扫描生成结果");selectedTask=selected;pictures=new File(selected,"eval_"+result.getInt("completed_steps")).listFiles((d,n)->n.matches("frame-\\d{4}\\.png"));if(pictures==null||pictures.length==0)throw new IOException("模型已保存，暂无评估图");Arrays.sort(pictures);
            info.setText("手机生成用时约 "+Math.round(result.getDouble("generation_and_prepare_ms")/1000)+" 秒 · "+result.getInt("gaussians")+" 个高斯\n下方为本次模型实际渲染图，仍包含背景。家具提取及新模型 AR 尚未接入。");showImage();
        }catch(Exception error){info.setText(error.getMessage());next.setEnabled(false);rotate.setEnabled(false);}
    }
    void showImage(){Bitmap old=bitmap;bitmap=BitmapFactory.decodeFile(pictures[index].getAbsolutePath());image.setImageBitmap(bitmap);if(old!=null)old.recycle();caption.setText("视角 "+(index+1)+" / "+pictures.length+" · 画面保留原始图像方向，可手动旋转");}
    @Override protected void onDestroy(){image.setImageDrawable(null);if(bitmap!=null)bitmap.recycle();super.onDestroy();}
}
