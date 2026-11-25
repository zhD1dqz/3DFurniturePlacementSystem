package com.furniture.chairdemo;
import android.view.View;
import android.widget.*;
import java.util.*;

final class CropEditor {
    final SceneViewerActivity activity;final LinearLayout panel,controls;final CheckBox preview;final TextView summary;
    final ArrayDeque<CropBox> history=new ArrayDeque<>();CropBox box;boolean updating=false,position=false,draft=false;
    CropEditor(SceneViewerActivity a){activity=a;box=new CropBox(a.focus[0],a.focus[1],a.focus[2],.9f,1,.9f,0);
        panel=new LinearLayout(a);panel.setOrientation(1);panel.setPadding(a.dp(10),0,a.dp(10),0);panel.setVisibility(View.GONE);
        summary=Ui.text(a,"转动模型，检查绿色框内要保留的范围。",13,Ui.MUTED);panel.addView(summary);
        LinearLayout tools=new LinearLayout(a);Button mode=Ui.plain(a,"调整位置/方向");mode.setOnClickListener(v->{position=!position;mode.setText(position?"调整大小":"调整位置/方向");rebuild();});tools.addView(mode,new LinearLayout.LayoutParams(0,a.dp(48),1));
        preview=new CheckBox(a);preview.setText("只看框内");preview.setTextColor(Ui.TEXT);preview.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));preview.setOnCheckedChangeListener((v,checked)->apply());tools.addView(preview,new LinearLayout.LayoutParams(0,a.dp(48),1));panel.addView(tools);
        controls=new LinearLayout(a);controls.setOrientation(1);ScrollView scroll=new ScrollView(a);scroll.addView(controls);panel.addView(scroll,new LinearLayout.LayoutParams(-1,a.dp(160)));
        LinearLayout actions=new LinearLayout(a);Button undo=Ui.plain(a,"撤销");undo.setOnClickListener(v->{if(!history.isEmpty()){box=history.pop();rebuild();apply();}});actions.addView(undo,new LinearLayout.LayoutParams(0,a.dp(48),1));
        Button cancel=Ui.plain(a,"取消裁剪");cancel.setOnClickListener(v->cancel());actions.addView(cancel,new LinearLayout.LayoutParams(0,a.dp(48),1));Button save=Ui.button(a,"保存家具",true);save.setOnClickListener(v->save());actions.addView(save,new LinearLayout.LayoutParams(0,a.dp(48),1));panel.addView(actions);rebuild();
    }
    void open(){if(activity.sceneRenderer.sourceRows==null){activity.message("模型还在载入，请稍后再试");return;}
        if(panel.getVisibility()!=View.VISIBLE){if(!draft){suggest();history.clear();draft=true;}panel.setVisibility(View.VISIBLE);rebuild();apply();}else panel.setVisibility(View.GONE);
    }
    void suggest(){// Local lower horizontal concentration suggests a floor; user can correct it.
        float[] rows=activity.sceneRenderer.sourceRows;float low=activity.focus[1]-.85f;int[] bins=new int[65];for(int k=0;k<rows.length;k+=37){float y=rows[k+1];if(Math.abs(rows[k]-activity.focus[0])<.5&&Math.abs(rows[k+2]-activity.focus[2])<.5&&rows[k+12]>.1&&y>=low&&y<low+.65)bins[Math.min(bins.length-1,(int)((y-low)/.01f))]++;}
        int mode=0;for(int i=1;i<bins.length;i++)if(bins[i]>bins[mode])mode=i;float floor=bins[mode]>=10?low+mode*.01f+.03f:activity.focus[1]-.45f;
        box=new CropBox(activity.focus[0],floor+.45f,activity.focus[2],.9f,.9f,.9f,0);
    }
    void cancel(){draft=false;panel.setVisibility(View.GONE);history.clear();activity.surface.queueEvent(()->activity.sceneRenderer.crop(null,false));activity.message("已恢复完整场景，原模型未改动");}
    void rebuild(){updating=true;controls.removeAllViews();if(position){slider("左右",box.x,activity.focus[0]-3,activity.focus[0]+3,0);slider("上下",box.y,activity.focus[1]-2,activity.focus[1]+2,1);slider("前后",box.z,activity.focus[2]-3,activity.focus[2]+3,2);slider("方向（度）",(float)Math.toDegrees(box.yaw),-180,180,6);}else{slider("宽",box.width,.1f,3,3);slider("高",box.height,.1f,3,4);slider("深",box.depth,.1f,3,5);}updating=false;}
    void slider(String label,float value,float min,float max,int axis){TextView text=new TextView(activity);controls.addView(text);SeekBar seek=new SeekBar(activity);seek.setMax(axis==6?360:600);seek.setProgress(Math.round((value-min)/(max-min)*seek.getMax()));text.setText(String.format(Locale.CHINA,"%s：%.2f",label,value));controls.addView(seek,new LinearLayout.LayoutParams(-1,activity.dp(28)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar v){history.push(box);}public void onStopTrackingTouch(SeekBar v){}public void onProgressChanged(SeekBar v,int progress,boolean user){if(!user||updating)return;float val=min+(max-min)*progress/v.getMax();float[] values={box.x,box.y,box.z,box.width,box.height,box.depth,box.yaw};values[axis]=axis==6?(float)Math.toRadians(val):val;box=new CropBox(values[0],values[1],values[2],values[3],values[4],values[5],values[6]);text.setText(String.format(Locale.CHINA,"%s：%.2f",label,val));apply();}});
    }
    void apply(){CropBox selected=box;boolean only=preview.isChecked();activity.surface.queueEvent(()->activity.sceneRenderer.crop(selected,only));}
    void save(){
        CropBox selected=box;
        NameDialog.show(activity,"保存家具",activity.editedFurnitureName,"家具名称（可选）","继续调整",true,name->{
            StudioDialog progress=StudioDialog.progress(activity,"保存家具","正在检查裁剪结果…");
            new Thread(()->{try{
                String id=CropStore.save(activity,activity.sceneId,selected,name);
                activity.runOnUiThread(()->{progress.dismiss();if(activity.isFinishing())return;
                    activity.startActivity(new android.content.Intent(activity,MainActivity.class).putExtra("model_id",id).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));activity.finish();});
            }catch(Exception e){activity.runOnUiThread(()->{progress.dismiss();activity.message(e.getMessage());});}},"SaveFurnitureCrop").start();
        });
    }
}
