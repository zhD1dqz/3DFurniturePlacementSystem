package com.furniture.chairdemo;
import android.view.*;
final class WindowLayout {
    static void safe(View root){root.setOnApplyWindowInsetsListener((v,insets)->{
        v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
    });root.requestApplyInsets();}
}
