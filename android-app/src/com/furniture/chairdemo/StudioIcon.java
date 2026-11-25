package com.furniture.chairdemo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small line icons drawn on a single 24-point grid. */
public final class StudioIcon extends Drawable {
    public enum Kind { HOUSE, LIBRARY, SCAN, MODEL, LAYOUT, ADD, MOVE, SAVE, TOOLS, SCENE, BACK, COMPUTER, PHONE, HISTORY, EDIT, DELETE }

    private final Kind kind;
    private final int size;
    private final ColorStateList colors;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();

    StudioIcon(Context context,Kind kind,ColorStateList colors){
        this.kind=kind;this.colors=colors;size=Ui.dp(context,20);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public boolean isStateful(){return colors.isStateful();}
    @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}

    private void line(Canvas canvas,float... points){
        path.reset();path.moveTo(points[0],points[1]);
        for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        canvas.drawPath(path,paint);
    }

    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);
        canvas.scale(bounds.width()/24f,bounds.height()/24f);
        paint.setColor(colors.getColorForState(getState(),colors.getDefaultColor()));
        switch(kind){
            case HOUSE:
                line(canvas,3,11,12,4,21,11);line(canvas,6,10,6,20,18,20,18,10);line(canvas,10,20,10,14,14,14,14,20);break;
            case LIBRARY:
                line(canvas,4,5,4,19,20,19);line(canvas,8,5,8,16);line(canvas,12,5,12,16);line(canvas,15,6,19,16);break;
            case SCAN:
                line(canvas,9,4,4,4,4,9);line(canvas,15,4,20,4,20,9);line(canvas,4,15,4,20,9,20);line(canvas,20,15,20,20,15,20);
                canvas.drawCircle(12,12,3,paint);break;
            case MODEL:
                line(canvas,12,3,20,7.5f,20,16.5f,12,21,4,16.5f,4,7.5f,12,3);
                line(canvas,4,7.5f,12,12,20,7.5f);line(canvas,12,12,12,21);break;
            case LAYOUT:
                canvas.drawRoundRect(4,4,20,20,2,2,paint);line(canvas,11,4,11,14,20,14);line(canvas,4,10,11,10);break;
            case ADD:
                canvas.drawCircle(12,12,8.5f,paint);line(canvas,8,12,16,12);line(canvas,12,8,12,16);break;
            case MOVE:
                line(canvas,4,12,20,12);line(canvas,12,4,12,20);line(canvas,7,9,4,12,7,15);line(canvas,17,9,20,12,17,15);
                line(canvas,9,7,12,4,15,7);line(canvas,9,17,12,20,15,17);break;
            case SAVE:
                line(canvas,5,4,17,4,20,7,20,20,4,20,4,4,5,4);line(canvas,8,4,8,9,16,9,16,4);line(canvas,8,20,8,14,16,14,16,20);break;
            case TOOLS:
                line(canvas,4,7,8,7);line(canvas,12,7,20,7);canvas.drawCircle(10,7,2,paint);
                line(canvas,4,17,13,17);line(canvas,17,17,20,17);canvas.drawCircle(15,17,2,paint);break;
            case SCENE:
                canvas.drawRoundRect(4,4,20,20,2,2,paint);line(canvas,8,9,16,9);line(canvas,8,15,16,15);break;
            case BACK:
                line(canvas,10,5,4,12,10,19);line(canvas,4,12,20,12);break;
            case COMPUTER:
                canvas.drawRoundRect(3,4,21,16,1.5f,1.5f,paint);line(canvas,12,16,12,20);line(canvas,8,20,16,20);break;
            case PHONE:
                canvas.drawRoundRect(7,2.5f,17,21.5f,2,2,paint);line(canvas,11,18,13,18);break;
            case HISTORY:
                canvas.drawCircle(12,12,8.5f,paint);line(canvas,12,7,12,12,16,14);break;
            case EDIT:
                line(canvas,4,20,5,15,16,4,20,8,9,19,4,20);line(canvas,13,7,17,11);break;
            case DELETE:
                line(canvas,4,6,20,6);line(canvas,9,6,9,3,15,3,15,6);line(canvas,6,6,7,20,17,20,18,6);
                line(canvas,10,10,10,16);line(canvas,14,10,14,16);break;
        }
        canvas.restore();
    }
}
