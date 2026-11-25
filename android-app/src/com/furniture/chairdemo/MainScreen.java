package com.furniture.chairdemo;

import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.opengl.GLSurfaceView;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class MainScreen {
    final MainActivity a;
    final LinearLayout home,previewHeader,roomTop,roomBottom,tools,footer,details;
    final FrameLayout viewport;
    final ScrollView toolScroll;
    final Button toolsToggle,scenes,calibrate,contact,reset,remove,saveLayout,moveGroup,roomBack;
    final CheckBox depth,edge,ruler,roomRuler;
    final TextView selection,homeStatus,homeBadge;
    private final LinearLayout[] toolPages=new LinearLayout[3];
    final Button[] toolTabs=new Button[3];
    private int toolHeightLimit;
    boolean toolsCollapsed=true;

    MainScreen(MainActivity activity){
        a=activity;
        a.getWindow().setStatusBarColor(Ui.BG);
        a.getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root=Ui.column(a);
        root.setBackgroundColor(Ui.BG);
        home=Ui.column(a);
        home.setPadding(a.dp(20),a.dp(12),a.dp(20),a.dp(14));
        LinearLayout header=row(home);
        header.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView mark=new android.widget.ImageView(a);
        mark.setImageDrawable(new StudioIcon(a,StudioIcon.Kind.HOUSE,ColorStateList.valueOf(Ui.ACCENT)));
        mark.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        mark.setBackground(Ui.shape(a,0xff24382a,12));
        header.addView(mark,new LinearLayout.LayoutParams(a.dp(38),a.dp(38)));
        TextView title=Ui.heading(a,"家居重建",24);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);
        titleParams.setMarginStart(a.dp(10));header.addView(title,titleParams);
        Button library=Ui.plain(a,"素材库");
        Ui.icon(library,StudioIcon.Kind.LIBRARY,false);
        library.setOnClickListener(v->a.showModels());
        header.addView(library,new LinearLayout.LayoutParams(-2,a.dp(48)));
        root.addView(home);

        viewport=new FrameLayout(a);
        viewport.setBackground(Ui.shape(a,0xff1b2520,22));
        a.surface=new GLSurfaceView(a);
        a.surface.setEGLContextClientVersion(3);
        a.surface.setPreserveEGLContextOnPause(true);
        a.renderer=a.new ChairRenderer();
        a.dimensionSummary=Ui.text(a,"",13,Ui.MUTED);
        a.loadDimensions();
        a.surface.setRenderer(a.renderer);
        viewport.addView(a.surface,new FrameLayout.LayoutParams(-1,-1));
        root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));

        previewHeader=new LinearLayout(a);
        previewHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity=Ui.column(a);
        identity.addView(Ui.text(a,"当前模型",12,Ui.MUTED));
        homeBadge=Ui.heading(a,"",18);
        homeBadge.setMaxLines(2);
        homeBadge.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(homeBadge);
        previewHeader.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        ruler=check("尺寸标尺",false);
        ruler.setOnCheckedChangeListener((v,on)->a.showSizeGuide=on);
        previewHeader.addView(ruler,new LinearLayout.LayoutParams(-2,a.dp(48)));
        overlay(previewHeader,Gravity.TOP,-1,16,11,0);

        roomTop=Ui.column(a);
        roomTop.setPadding(a.dp(4),0,a.dp(4),0);
        roomTop.setBackground(Ui.surface(a,Ui.CAMERA_PANEL,17));
        LinearLayout roomActions=row(roomTop);
        roomActions.setGravity(Gravity.CENTER_VERTICAL);
        roomBack=Ui.plain(a,"");Ui.icon(roomBack,StudioIcon.Kind.BACK,false);
        roomBack.setContentDescription("返回首页");roomBack.setOnClickListener(v->a.requestLeaveRoom());
        roomActions.addView(roomBack,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));
        TextView roomTitle=Ui.heading(a,"房间布置",17);
        roomActions.addView(roomTitle,new LinearLayout.LayoutParams(0,-2,1));
        scenes=Ui.plain(a,"家具 · 0");Ui.icon(scenes,StudioIcon.Kind.SCENE,false);
        scenes.setOnClickListener(v->a.showSceneObjects());
        roomActions.addView(scenes,new LinearLayout.LayoutParams(-2,a.dp(48)));
        overlay(roomTop,Gravity.TOP,-1,14,9,0);

        roomBottom=Ui.column(a);
        details=Ui.column(a);
        details.setPadding(a.dp(8),a.dp(6),a.dp(8),a.dp(6));
        details.setBackground(Ui.surface(a,0xf0111714,18));
        // The panel consumes touches in its own bounds only. The camera keeps its size.
        details.setClickable(true);
        LinearLayout tabs=row(details);
        String[] tabNames={"家具","显示","布局"};
        for(int i=0;i<tabNames.length;i++){
            final int page=i;
            toolTabs[i]=Ui.tab(a,tabNames[i]);
            toolTabs[i].setOnClickListener(v->selectTools(page));
            tabs.addView(toolTabs[i],new LinearLayout.LayoutParams(0,a.dp(43),1));
        }
        tools=Ui.column(a);
        tools.setPadding(0,a.dp(8),0,0);
        for(int i=0;i<toolPages.length;i++){
            toolPages[i]=Ui.column(a);tools.addView(toolPages[i]);
        }
        selection=Ui.text(a,"",15,Ui.TEXT);
        selection.setTypeface(null,Typeface.BOLD);
        selection.setPadding(a.dp(6),a.dp(4),a.dp(6),0);
        toolPages[0].addView(selection);
        a.dimensionSummary.setPadding(a.dp(6),a.dp(3),a.dp(6),a.dp(4));
        toolPages[0].addView(a.dimensionSummary);

        LinearLayout sizes=row(toolPages[0]);
        add(sizes,"设置长宽高",false,()->a.showDimensions(null));
        a.lockButton=add(sizes,"放置后自动锁定",false,()->a.surface.queueEvent(()->{
            if(a.renderer.anchor==null)return;
            a.renderer.placementLocked=!a.renderer.placementLocked;
            a.placementFeedback();
            a.updateLockLabel();
        }));
        LinearLayout ground=row(toolPages[0]);
        calibrate=add(ground,"接地尺寸校准",false,()->a.groundPanel.open());
        contact=add(ground,"接地微调",false,a::showContactAdjustment);
        a.groundPanel=new GroundPanel(a);
        toolPages[0].addView(a.groundPanel.panel);

        roomRuler=check("尺寸标尺",false);
        roomRuler.setOnCheckedChangeListener((v,on)->a.showSizeGuide=on);
        depth=check("深度图",false);
        depth.setOnCheckedChangeListener((v,on)->a.showDepthMap=on);
        edge=check("边缘平滑",true);
        edge.setOnCheckedChangeListener((v,on)->a.smoothOcclusionEdges=on);
        for(CheckBox control:new CheckBox[]{roomRuler,depth,edge})toolPages[1].addView(control,new LinearLayout.LayoutParams(-1,a.dp(48)));

        LinearLayout layout=row(toolPages[2]);
        add(layout,"读取布局",false,()->RoomLayouts.show(a));
        remove=add(layout,"移除选中",false,()->a.surface.queueEvent(()->a.renderer.room.removeSelected()));
        LinearLayout rotate=row(toolPages[2]);
        add(rotate,"整组左转 15°",false,()->a.surface.queueEvent(()->a.renderer.room.rotateGroup((float)Math.PI/12)));
        add(rotate,"整组右转 15°",false,()->a.surface.queueEvent(()->a.renderer.room.rotateGroup(-(float)Math.PI/12)));
        reset=add(row(toolPages[2]),"重置场景",false,a::resetRoom);
        toolHeightLimit=a.dp(220);
        toolScroll=new ScrollView(a){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int available=View.MeasureSpec.getSize(heightSpec);
                int maximum=available>0?Math.min(available,toolHeightLimit):toolHeightLimit;
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST));
            }
        };
        toolScroll.addView(tools);
        details.addView(toolScroll,new LinearLayout.LayoutParams(-1,-2));
        selectTools(0);
        viewport.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->{
            int height=Math.min(a.dp(270),Math.round((b-t)*.31f));
            if(height>0&&toolHeightLimit!=height){
                toolHeightLimit=height;
                toolScroll.requestLayout();
            }
        });

        a.status=Ui.text(a,"",13,Ui.TEXT);
        a.status.setMaxLines(2);
        a.status.setEllipsize(TextUtils.TruncateAt.END);
        a.status.setPadding(a.dp(12),a.dp(7),a.dp(12),a.dp(7));
        a.status.setGravity(Gravity.CENTER);
        a.status.setBackground(Ui.shape(a,Ui.CAMERA_PANEL,14));
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-2,-2);
        statusParams.gravity=Gravity.CENTER_HORIZONTAL;
        statusParams.setMargins(a.dp(8),0,a.dp(8),a.dp(8));
        roomBottom.addView(a.status,statusParams);
        LinearLayout.LayoutParams panelParams=new LinearLayout.LayoutParams(-1,-2);
        panelParams.bottomMargin=a.dp(6);
        roomBottom.addView(details,panelParams);
        LinearLayout quick=row(roomBottom);
        quick.setPadding(a.dp(4),a.dp(2),a.dp(4),a.dp(2));
        quick.setBackground(Ui.surface(a,Ui.CAMERA_PANEL,20));
        dock(quick,"添加家具",StudioIcon.Kind.ADD,a::showRoomModels);
        moveGroup=dock(quick,"移动整组",StudioIcon.Kind.MOVE,a::showGroupPosition);
        saveLayout=dock(quick,"保存布局",StudioIcon.Kind.SAVE,()->RoomLayouts.save(a));
        toolsToggle=dock(quick,"工具",StudioIcon.Kind.TOOLS,()->setToolsCollapsed(!toolsCollapsed));
        overlay(roomBottom,Gravity.BOTTOM,-1,14,0,12);

        footer=Ui.column(a);
        footer.setPadding(a.dp(20),a.dp(10),a.dp(20),a.dp(12));
        homeStatus=Ui.text(a,"",13,Ui.MUTED);
        homeStatus.setPadding(a.dp(4),a.dp(5),a.dp(4),0);
        footer.addView(homeStatus);
        LinearLayout homeActions=row(footer);
        a.arButton=Ui.button(a,"放进房间",true);
        a.arButton.setTextSize(16);
        a.arButton.setOnClickListener(v->a.enterRoom());
        homeActions.addView(a.arButton,new LinearLayout.LayoutParams(0,a.dp(52),1));
        Button edit=Ui.plain(a,"查看与裁剪");
        edit.setOnClickListener(v->a.openModelScene());
        LinearLayout.LayoutParams editParams=new LinearLayout.LayoutParams(-2,a.dp(52));editParams.setMarginStart(a.dp(8));
        homeActions.addView(edit,editParams);
        TextView create=Ui.heading(a,"新建模型",15);
        create.setPadding(0,a.dp(14),0,a.dp(7));footer.addView(create);
        LinearLayout workflow=row(footer);
        workflow.setBackground(Ui.surface(a,Ui.PANEL,16));
        shortcut(workflow,"扫描家具",StudioIcon.Kind.SCAN,a::startScan);
        View separator=new View(a);separator.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams separatorParams=new LinearLayout.LayoutParams(a.dp(1),a.dp(24));
        separatorParams.gravity=Gravity.CENTER_VERTICAL;workflow.addView(separator,separatorParams);
        shortcut(workflow,"生成模型",StudioIcon.Kind.MODEL,a::startGeneration);
        root.addView(footer);

        a.setContentView(root);
        WindowLayout.safe(root);
        setRoom(false);
        if(a.startupNote!=null)showStatus(a.startupNote);
    }

    private void overlay(View child,int gravity,int width,int horizontal,int top,int bottom){
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,-2,gravity);
        params.setMargins(a.dp(horizontal),a.dp(top),a.dp(horizontal),a.dp(bottom));
        viewport.addView(child,params);
    }
    private LinearLayout row(LinearLayout parent){
        LinearLayout row=new LinearLayout(a);
        parent.addView(row);
        return row;
    }
    private Button add(LinearLayout row,String label,boolean primary,Runnable action){
        Button button=Ui.button(a,label,primary);
        button.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,a.dp(48),1);
        params.setMargins(a.dp(4),a.dp(3),a.dp(4),a.dp(3));
        row.addView(button,params);
        return button;
    }
    private void selectTools(int page){
        for(int i=0;i<toolPages.length;i++){
            toolTabs[i].setSelected(i==page);
            toolPages[i].setVisibility(i==page?View.VISIBLE:View.GONE);
        }
        if(page!=0)a.groundPanel.close();
        toolScroll.scrollTo(0,0);
    }
    private Button dock(LinearLayout row,String label,StudioIcon.Kind icon,Runnable action){
        Button button=Ui.plain(a,label);
        button.setTextSize(12);Ui.icon(button,icon,true);
        button.setOnClickListener(v->action.run());
        row.addView(button,new LinearLayout.LayoutParams(0,a.dp(56),1));
        return button;
    }
    private void shortcut(LinearLayout row,String label,StudioIcon.Kind icon,Runnable action){
        Button button=Ui.plain(a,label);
        Ui.icon(button,icon,false);button.setOnClickListener(v->action.run());
        button.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        button.setPadding(a.dp(16),0,a.dp(6),0);
        row.addView(button,new LinearLayout.LayoutParams(0,a.dp(51),1));
    }
    private CheckBox check(String label,boolean checked){
        CheckBox box=new CheckBox(a);
        box.setText(label);
        box.setTextColor(Ui.MUTED);
        box.setTextSize(13);
        box.setChecked(checked);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        return box;
    }
    void showStatus(String message){
        TextView label=a.roomMode?a.status:homeStatus;
        if(!TextUtils.equals(label.getText(),message))label.setText(message);
        label.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
    }
    void setToolsCollapsed(boolean collapsed){
        toolsCollapsed=collapsed;
        if(collapsed)a.groundPanel.close();
        int visibility=a.roomMode&&!collapsed?View.VISIBLE:View.GONE;
        details.setVisibility(visibility);
        toolScroll.setVisibility(visibility);
        if(!collapsed)toolScroll.scrollTo(0,0);
        toolsToggle.setText(collapsed?"工具":"收起工具");
        toolsToggle.setContentDescription(collapsed?"展开房间调整工具":"收起房间调整工具");
    }
    void setRoom(boolean on){
        a.roomMode=on;
        home.setVisibility(on?View.GONE:View.VISIBLE);
        footer.setVisibility(on?View.GONE:View.VISIBLE);
        previewHeader.setVisibility(on?View.GONE:View.VISIBLE);
        roomTop.setVisibility(on?View.VISIBLE:View.GONE);
        roomBottom.setVisibility(on?View.VISIBLE:View.GONE);
        a.status.setVisibility(View.GONE);
        homeStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams viewportParams=(LinearLayout.LayoutParams)viewport.getLayoutParams();
        viewportParams.leftMargin=viewportParams.rightMargin=on?0:a.dp(20);
        viewport.setLayoutParams(viewportParams);
        viewport.setClipToOutline(!on);
        if(on)roomRuler.setChecked(a.showSizeGuide);
        else ruler.setChecked(a.showSizeGuide);
        selectTools(0);
        setToolsCollapsed(true);
        refresh();
    }
    void refresh(){
        RoomScene room=a.renderer.room;
        int count=room.size();
        scenes.setText("家具 · "+count);
        saveLayout.setEnabled(count>0);
        moveGroup.setEnabled(count>0&&room.pending==null);
        String name=ModelStore.BUILTIN.equals(a.activeModelId)?"示例椅子":a.activeModel==null?"家具":a.activeModel.optString("name","家具");
        selection.setText(room.pendingLayout!=null?"等待选择整组落点":room.pending!=null?"待放置 · "+name:room.selected!=null?"已选中 · "+name:"当前家具 · "+name);
        homeBadge.setText(name);
        calibrate.setEnabled(a.croppedFurniture());
        contact.setEnabled(a.calibratedFurniture());
        remove.setEnabled(room.pendingLayout==null&&room.selected!=null&&room.pending!=room.selected);
        a.lockButton.setEnabled(a.renderer.anchor!=null);
        a.lockButton.setText(a.renderer.anchor==null?"放置后自动锁定":a.renderer.placementLocked?"解锁家具":"锁定家具");
    }
}
