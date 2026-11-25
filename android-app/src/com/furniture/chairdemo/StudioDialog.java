package com.furniture.chairdemo;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** In-app screens and compact confirmations share the same layout and controls. */
public final class StudioDialog extends Dialog {
    final LinearLayout body;
    private final LinearLayout root;
    private final boolean full;

    private StudioDialog(Activity a,String title,StudioIcon.Kind icon,boolean full){
        super(a,full?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Dialog_NoActionBar);
        this.full=full;
        root=Ui.column(a);
        if(full){
            root.setBackgroundColor(Ui.BG);
            root.addView(Ui.toolbar(a,title,this::dismiss));
            Ui.divider(root);
            WindowLayout.safe(root);
        }else{
            int edge=Ui.dp(a,18);
            root.setPadding(edge,edge,edge,edge);
            root.setBackground(Ui.surface(a,Ui.PANEL,22));
            LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
            if(icon!=null){
                ImageView mark=new ImageView(a);
                int tint=icon==StudioIcon.Kind.DELETE?Ui.DANGER:Ui.ACCENT;
                mark.setImageDrawable(new StudioIcon(a,icon,ColorStateList.valueOf(tint)));
                mark.setBackground(Ui.shape(a,Ui.BG,11));mark.setScaleType(ImageView.ScaleType.CENTER);
                LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(Ui.dp(a,40),Ui.dp(a,40));
                slot.setMarginEnd(Ui.dp(a,12));header.addView(mark,slot);
            }
            TextView heading=Ui.heading(a,title,20);heading.setMaxLines(2);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
            root.addView(header);
        }
        ScrollView scroll=new ScrollView(a){
            @Override protected void onMeasure(int width,int height){
                if(!full){
                    int cap=Math.round(getResources().getDisplayMetrics().heightPixels*.46f);
                    if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)cap=Math.min(cap,MeasureSpec.getSize(height));
                    height=MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST);
                }
                super.onMeasure(width,height);
            }
        };
        scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        body=Ui.column(a);
        int inset=full?Ui.dp(a,18):0;
        body.setPadding(inset,Ui.dp(a,full?16:12),inset,Ui.dp(a,full?20:0));
        scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,full?0:-2,full?1:0));
        setContentView(root);
        Window window=getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(full?Ui.BG:Color.TRANSPARENT));
            if(!full){
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(.62f);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        }
    }

    static StudioDialog page(Activity a,String title){return new StudioDialog(a,title,null,true);}
    static StudioDialog panel(Activity a,String title,StudioIcon.Kind icon){return new StudioDialog(a,title,icon,false);}

    @Override protected void onStart(){
        super.onStart();
        Window window=getWindow();if(window==null)return;
        if(full){
            window.setLayout(-1,-1);window.setStatusBarColor(Ui.BG);window.setNavigationBarColor(Ui.BG);
            root.requestApplyInsets();
        }else{
            int available=getContext().getResources().getDisplayMetrics().widthPixels-Ui.dp(getContext(),32);
            window.setLayout(Math.min(available,Ui.dp(getContext(),440)),-2);
        }
    }

    void message(String message){
        TextView note=Ui.text(getContext(),message==null?"请稍后重试。":message,14,Ui.MUTED);
        note.setLineSpacing(Ui.dp(getContext(),3),1);body.addView(note);
    }

    void choice(StudioIcon.Kind icon,String title,String note,Runnable action){
        Button button=Ui.cardAction(getContext(),title,note,icon);
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33bde8bb),
                Ui.surface(getContext(),full?Ui.PANEL:Ui.BG,14),null));
        button.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(-1,-2);slot.bottomMargin=Ui.dp(getContext(),8);
        body.addView(button,slot);
    }

    void empty(String title,String detail){
        LinearLayout card=Ui.column(getContext());int inset=Ui.dp(getContext(),16);
        card.setPadding(inset,inset,inset,inset);card.setBackground(Ui.surface(getContext(),Ui.PANEL,16));
        card.addView(Ui.heading(getContext(),title,15));Ui.space(card,4);
        card.addView(Ui.text(getContext(),detail,13,Ui.MUTED));body.addView(card);
    }

    Button actions(String secondary,Runnable alternate,String primary,Runnable action,boolean destructive){
        LinearLayout row=new LinearLayout(getContext());
        int inset=Ui.dp(getContext(),full?18:0);row.setPadding(inset,Ui.dp(getContext(),14),inset,Ui.dp(getContext(),full?12:0));
        if(secondary!=null){
            Button other=Ui.button(getContext(),secondary,false);
            other.setOnClickListener(v->{if(alternate==null)dismiss();else alternate.run();});
            LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,Ui.dp(getContext(),48),1);
            if(primary!=null)slot.setMarginEnd(Ui.dp(getContext(),8));row.addView(other,slot);
        }
        Button main=null;
        if(primary!=null){
            main=destructive?Ui.danger(getContext(),primary):Ui.button(getContext(),primary,true);
            main.setEnabled(action!=null);if(action!=null)main.setOnClickListener(v->action.run());
            row.addView(main,new LinearLayout.LayoutParams(0,Ui.dp(getContext(),48),1));
        }
        root.addView(row);
        return main;
    }

    public static void confirm(Activity a,String title,StudioIcon.Kind icon,String message,String secondary,String primary,boolean destructive,Runnable action){
        StudioDialog dialog=panel(a,title,icon);dialog.message(message);
        dialog.actions(secondary,null,primary,()->{dialog.dismiss();action.run();},destructive);dialog.show();
    }

    static StudioDialog progress(Activity a,String title,String message){
        StudioDialog dialog=panel(a,title,null);
        ProgressBar spinner=new ProgressBar(a);spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(Ui.dp(a,24),Ui.dp(a,24));slot.bottomMargin=Ui.dp(a,8);
        dialog.body.addView(spinner,slot);dialog.message(message);dialog.setCancelable(false);dialog.show();return dialog;
    }
}
