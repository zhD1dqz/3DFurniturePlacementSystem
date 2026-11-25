package com.furniture.chairdemo;
import android.app.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.Locale;
import org.json.*;

final class GroundPanel {
    final MainActivity activity;final LinearLayout panel;final EditText height;final TextView label;final SeekBar slider;String base;float suggested,ground;
    GroundPanel(MainActivity a){activity=a;panel=new LinearLayout(a);panel.setOrientation(1);panel.setPadding(a.dp(12),0,a.dp(12),0);panel.setVisibility(View.GONE);
        TextView note=new TextView(a);note.setText("按地面到家具最高点的实测高度等比校准。绿色线为建议接地面，可旋转查看后调整；宽深暂为估计。");panel.addView(note);
        height=new EditText(a);height.setHint("实际总高（厘米）");height.setSingleLine(true);height.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);panel.addView(height);
        label=new TextView(a);panel.addView(label);slider=new SeekBar(a);slider.setMax(200);slider.setProgress(100);panel.addView(slider,new LinearLayout.LayoutParams(-1,a.dp(30)));slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}public void onProgressChanged(SeekBar v,int p,boolean user){if(user){ground=suggested+(p-100)*.001f;preview();}}});
        LinearLayout actions=new LinearLayout(a);Button cancel=new Button(a);cancel.setText("取消");cancel.setOnClickListener(v->close());actions.addView(cancel,new LinearLayout.LayoutParams(0,a.dp(42),1));Button save=new Button(a);save.setText("保存校准副本");save.setOnClickListener(v->save());actions.addView(save,new LinearLayout.LayoutParams(0,a.dp(42),1));panel.addView(actions);
    }
    void open(){if(!activity.croppedFurniture()){activity.message("请先选择扫描生成的家具");return;}if(activity.session!=null&&activity.renderer.placementLocked){activity.message("请先解锁选中的家具，再校准");return;}if(panel.getVisibility()==View.VISIBLE){close();return;}
        try{base=GroundCalibration.base(activity,activity.activeModelId);float[] range=GroundCalibration.suggest(GroundCalibration.model(activity,base));suggested=range[0];ground=activity.calibratedFurniture()?(float)activity.activeModel.getJSONObject("calibration").getDouble("ground_y_source"):suggested;slider.setProgress(Math.max(0,Math.min(200,100+Math.round((ground-suggested)*1000))));
            if(activity.calibratedFurniture())height.setText(String.format(Locale.US,"%.1f",activity.requestedHeight*100));panel.setVisibility(View.VISIBLE);preview();
        }catch(Exception e){activity.message("无法校准："+e.getMessage());}
    }
    void preview(){float y=ground;if(activity.calibratedFurniture())try{JSONObject c=activity.activeModel.getJSONObject("calibration");y=(float)((ground-c.getDouble("ground_y_source"))*c.getDouble("uniform_scale"));}catch(Exception ignored){}final float plane=y;activity.surface.queueEvent(()->{activity.groundGuideY=plane;activity.showGroundGuide=true;});label.setText(String.format(Locale.CHINA,"接地面相对建议位置：%+.1f cm",(ground-suggested)*100));}
    void close(){panel.setVisibility(View.GONE);activity.surface.queueEvent(()->activity.showGroundGuide=false);}
    void save(){final float measured;try{measured=MainActivity.centimetres(height.getText().toString());}catch(Exception e){height.setError("请输入实测高度，单位厘米");return;}final String source=base;final float plane=ground;ProgressDialog progress=ProgressDialog.show(activity,"保存校准","正在按实测高度校准…",true,false);
        new Thread(()->{try{String id=GroundCalibration.save(activity,source,plane,measured);activity.runOnUiThread(()->{progress.dismiss();activity.selectModel(id);});}catch(Exception e){activity.runOnUiThread(()->{progress.dismiss();activity.message(e.getMessage());});}},"CalibrateFurniture").start();}
}
