package com.furniture.chairdemo;
import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.widget.*;

/** Small native visual system; no remote fonts or UI runtime. */
public final class Ui {
    public static final int BG=0xff111714,PANEL=0xff1d2722,TEXT=0xfff0f4ec,MUTED=0xffb0bdb2,ACCENT=0xffbde8bb;
    static final int DANGER=0xfff4b6ad;
    static final int LINE=0xff34413a, INK=0xff173321, CAMERA_PANEL=0xf2202a25;
    public static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    public static GradientDrawable surface(Context c,int color,int radius){GradientDrawable shape=shape(c,color,radius);shape.setStroke(dp(c,1),LINE);return shape;}
    public static TextView text(Context c,String value,int size,int color){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    public static Button button(Context c,String label,boolean primary){
        Button b=new Button(c);
        b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(c,8),0,dp(c,8),0);
        int[] disabled={-android.R.attr.state_enabled};
        b.setTextColor(new ColorStateList(new int[][]{disabled,new int[0]},new int[]{0xff8a998e,primary?INK:TEXT}));
        StateListDrawable backgrounds=new StateListDrawable();
        backgrounds.addState(disabled,shape(c,0xff252f29,14));
        backgrounds.addState(new int[0],primary?shape(c,ACCENT,14):surface(c,PANEL,14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x337db68a),backgrounds,null));
        b.setStateListAnimator(null);
        return b;
    }
    public static Button plain(Context c,String label){
        Button b=button(c,label,false);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33bde8bb),null,shape(c,0xffffffff,10)));
        return b;
    }
    static Button danger(Context c,String label){
        Button b=button(c,label,false);
        b.setTextColor(0xff411b18);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33411b18),shape(c,DANGER,14),null));
        return b;
    }
    static Button cardAction(Context c,String title,String note,StudioIcon.Kind kind){
        Button b=plain(c,title);icon(b,kind,false);
        b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setTextSize(15);b.setMaxLines(3);
        b.setEllipsize(TextUtils.TruncateAt.END);b.setCompoundDrawablePadding(dp(c,14));
        b.setPaddingRelative(dp(c,14),dp(c,9),dp(c,12),dp(c,9));b.setMinHeight(dp(c,note.isEmpty()?54:66));
        if(!note.isEmpty()){
            b.setTypeface(null,Typeface.NORMAL);
            SpannableString label=new SpannableString(title+"\n"+note);int start=title.length()+1;
            label.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,title.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(MUTED),start,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new RelativeSizeSpan(.86f),start,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setText(label);
        }
        return b;
    }
    static EditText field(Context c,String value,String hint,int inputType){
        EditText field=new EditText(c);field.setSingleLine(true);field.setInputType(inputType);
        field.setText(value);field.setHint(hint);field.setTextSize(16);field.setTextColor(TEXT);field.setHintTextColor(MUTED);
        field.setHighlightColor(0x55bde8bb);field.setPaddingRelative(dp(c,14),dp(c,10),dp(c,14),dp(c,10));field.setMinHeight(dp(c,52));
        StateListDrawable backgrounds=new StateListDrawable();
        GradientDrawable active=shape(c,BG,13);active.setStroke(dp(c,1),ACCENT);
        backgrounds.addState(new int[]{android.R.attr.state_focused},active);
        backgrounds.addState(new int[0],surface(c,BG,13));field.setBackground(backgrounds);
        return field;
    }
    static Button tab(Context c,String label){
        Button button=plain(c,label);
        int[] active={android.R.attr.state_selected};
        button.setTextColor(new ColorStateList(new int[][]{active,new int[0]},new int[]{ACCENT,MUTED}));
        StateListDrawable states=new StateListDrawable();
        states.addState(active,shape(c,0xff283d2f,10));
        states.addState(new int[0],shape(c,0x00000000,10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33bde8bb),states,null));
        return button;
    }
    static void icon(Button button,StudioIcon.Kind kind,boolean stacked){
        StudioIcon icon=new StudioIcon(button.getContext(),kind,button.getTextColors());
        if(stacked){
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(null,icon,null,null);
            button.setPadding(dp(button.getContext(),4),dp(button.getContext(),9),dp(button.getContext(),4),dp(button.getContext(),6));
            button.setCompoundDrawablePadding(dp(button.getContext(),3));
        }else{
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon,null,null,null);
            button.setCompoundDrawablePadding(dp(button.getContext(),6));
        }
    }
    public static TextView heading(Context c,String label,int size){TextView title=text(c,label,size,TEXT);title.setTypeface(null,Typeface.BOLD);return title;}
    public static void divider(LinearLayout group){View line=new View(group.getContext());line.setBackgroundColor(LINE);group.addView(line,new LinearLayout.LayoutParams(-1,dp(group.getContext(),1)));}
    public static LinearLayout toolbar(Activity activity,String label,Runnable back){
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button close=plain(activity,"←");close.setContentDescription("返回");close.setTextSize(22);close.setOnClickListener(v->back.run());
        row.addView(close,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));
        TextView title=heading(activity,label,23);
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-2,1);size.setMarginStart(dp(activity,6));row.addView(title,size);
        return row;
    }
    public static void space(LinearLayout parent,int height){View v=new View(parent.getContext());parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),height)));}
    public static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(1);return v;}
    public static LinearLayout.LayoutParams margin(Context c,int width,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height<0?height:dp(c,height));p.setMargins(dp(c,4),dp(c,4),dp(c,4),dp(c,4));return p;}
}
