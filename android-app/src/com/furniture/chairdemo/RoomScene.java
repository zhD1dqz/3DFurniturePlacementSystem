package com.furniture.chairdemo;
import android.opengl.*;
import com.google.ar.core.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** GL-thread owned scene. Every instance has independent anchor, yaw, size and lock. */
final class RoomScene {
    static final class Furniture {
        RoomAsset asset;Anchor anchor;Pose testPose;float width,depth,height,contact,yaw;boolean locked;String sizeMode;
        final float[] model=new float[16],vm=new float[16];
        Furniture(RoomAsset a){asset=a;width=a.userWidth;depth=a.userDepth;height=a.userHeight;contact=a.contact;sizeMode=a.sizeMode;}
        Pose pose(){return anchor==null?testPose:anchor.getPose();}
        boolean tracked(){return anchor!=null?anchor.getTrackingState()==TrackingState.TRACKING:testPose!=null;}
        void matrix(){pose().toMatrix(model,0);Matrix.rotateM(model,0,(float)Math.toDegrees(yaw),0,1,0);Matrix.translateM(model,0,0,contact,0);Matrix.scaleM(model,0,width/asset.width,height/asset.height,depth/asset.depth);}
        void detach(){if(anchor!=null)anchor.detach();anchor=null;testPose=null;}
    }
    final MainActivity a;final ArrayList<Furniture> items=new ArrayList<>();final java.util.concurrent.ConcurrentHashMap<String,RoomAsset> cache=new java.util.concurrent.ConcurrentHashMap<>();
    volatile Furniture selected,pending;volatile int itemCount;int transformTex;FloatBuffer upload;float[] sortDepth=new float[0];Integer[] order=new Integer[0];int[] owners=new int[0],indices=new int[0];int drawCount;long frames;
    ArrayList<Furniture> pendingLayout;
    private Pose trackedCamera;
    private boolean choosingGroupPosition;
    RoomScene(MainActivity a){this.a=a;}
    int size(){return itemCount;}
    void sync(){Furniture f=selected;if(f==null)return;f.width=a.requestedWidth;f.depth=a.requestedDepth;f.height=a.requestedHeight;f.contact=a.contactOffsetM;f.yaw=a.renderer.yaw;f.locked=a.renderer.placementLocked;f.asset.userWidth=f.width;f.asset.userDepth=f.depth;f.asset.userHeight=f.height;f.asset.contact=f.contact;f.asset.confirmed=a.dimensionsConfirmed;f.sizeMode=a.sizeMode;f.asset.sizeMode=f.sizeMode;}
    void select(Furniture f){if(f==null)return;sync();choosingGroupPosition=false;selected=f;publish();}
    void publish(){Furniture f=selected;if(f==null)return;a.activeModelId=f.asset.id;a.activeModel=f.asset.metadata;a.nominalWidth=f.asset.width;a.nominalDepth=f.asset.depth;a.nominalHeight=f.asset.height;a.requestedWidth=f.width;a.requestedDepth=f.depth;a.requestedHeight=f.height;a.contactOffsetM=f.contact;a.dimensionsConfirmed=f.asset.confirmed;a.sizeMode=f.sizeMode;a.renderer.yaw=f.yaw;a.renderer.placementLocked=f.locked;a.renderer.anchor=f.anchor;a.runOnUiThread(()->{a.refreshDimensionSummary();a.updateLockLabel();if(a.ui!=null)a.ui.refresh();});}
    void queue(RoomAsset asset){sync();pendingLayout=null;choosingGroupPosition=false;cache.put(asset.id,asset);pending=new Furniture(asset);selected=pending;publish();a.message("已选择家具，点击地面添加；已有摆放会保留");}
    void replace(RoomAsset asset){sync();cache.put(asset.id,asset);if(selected==null){queue(asset);return;}selected.asset=asset;selected.width=asset.userWidth;selected.depth=asset.userDepth;selected.height=asset.userHeight;selected.contact=asset.contact;selected.sizeMode=asset.sizeMode;publish();}
    void clear(){for(Furniture f:items)f.detach();items.clear();pending=selected=null;pendingLayout=null;choosingGroupPosition=false;itemCount=0;a.renderer.anchor=null;}
    void removeSelected(){sync();Furniture f=selected;if(f==null)return;if(f==pending){pending=null;}else{items.remove(f);f.detach();}itemCount=items.size();selected=null;if(!items.isEmpty()){selected=items.get(items.size()-1);publish();}else queue(f.asset);a.runOnUiThread(()->a.ui.refresh());}
    void start(RoomAsset asset){clear();queue(asset);}
    void add(Anchor anchor){if(pending==null)return;sync();pending.anchor=anchor;pending.locked=true;items.add(pending);selected=pending;pending=null;itemCount=items.size();publish();}
    void tap(Frame frame,float x,float y,MainActivity.ChairRenderer r){
        if(pendingLayout!=null||choosingGroupPosition){
            HitResult hit=floorHit(frame,x,y);
            if(hit==null){a.message("这里还没有识别到地面，请对准有纹理的地面再点");return;}
            try {
                if(pendingLayout!=null)restoreAt(hit.getHitPose());
                else moveGroupTo(hit.getHitPose());
            } catch(Exception e){a.message("整组放置失败："+e.getMessage());}
            return;
        }
        sync();
        if(pending==null){
            Furniture picked=pick(x,y,r);
            if(picked!=null){select(picked);return;}
            if(selected==null||selected.locked){a.message("点击家具可选中，解锁后可以移动");return;}
        }
        HitResult hit=floorHit(frame,x,y);
        if(hit==null){a.message("这里还没有识别到地面，请缓慢移动手机后再点");return;}
        Anchor anchor=hit.createAnchor();
        if(pending!=null){add(anchor);a.message("家具已放置 · 已锁定");}
        else {selected.detach();selected.anchor=anchor;selected.locked=true;publish();a.message("家具已移动 · 已锁定");}
        a.placementFeedback();
    }
    private HitResult floorHit(Frame frame,float x,float y){
        for(HitResult hit:frame.hitTest(x,y)){
            if(!(hit.getTrackable() instanceof Plane))continue;
            Plane plane=(Plane)hit.getTrackable();
            if(plane.getType()==Plane.Type.HORIZONTAL_UPWARD_FACING&&plane.isPoseInPolygon(hit.getHitPose()))return hit;
        }
        return null;
    }
    void updateCameraPose(Pose camera){trackedCamera=camera;}
    String placementHint(boolean locked){
        if(pendingLayout!=null||choosingGroupPosition)return "点地面，选择第一件家具的落点";
        if(pending!=null)return "点击地面添加家具";
        return locked?"已锁定 · 点家具可切换选中":"已解锁 · 点击地面移动，单指旋转";
    }
    static JSONArray numbers(float[] values)throws Exception{JSONArray out=new JSONArray();for(float v:values)out.put(v);return out;}
    static float[] vector(JSONArray values)throws Exception{float[] out=new float[values.length()];for(int i=0;i<out.length;i++)out[i]=(float)values.getDouble(i);return out;}
    JSONArray snapshot()throws Exception{
        sync();if(items.isEmpty()||pendingLayout!=null)throw new java.io.IOException("请先在地面放置家具");
        if(items.size()>20)throw new java.io.IOException("单个布局最多保存20件家具");for(Furniture f:items)if(!f.tracked())throw new java.io.IOException("定位暂时丢失，请恢复定位后保存");
        Pose origin=items.get(0).pose().inverse();JSONArray out=new JSONArray();
        for(Furniture f:items){Pose relative=origin.compose(f.pose());out.put(new JSONObject().put("model_id",f.asset.id).put("translation",numbers(relative.getTranslation())).put("rotation",numbers(relative.getRotationQuaternion())).put("width",f.width).put("depth",f.depth).put("height",f.height).put("yaw",f.yaw).put("contact",f.contact).put("size_mode",f.sizeMode).put("locked",f.locked));}return out;
    }
    void queueLayout(ArrayList<Furniture> prepared){clear();pendingLayout=prepared;selected=prepared.get(0);publish();a.message("点击地面指定整组布局的位置；放好后可整组旋转调整朝向");a.runOnUiThread(()->a.ui.refresh());}
    void restoreAt(Pose origin)throws Exception{
        ArrayList<Furniture> prepared=pendingLayout;if(prepared==null)return;ArrayList<Anchor> anchors=new ArrayList<>();
        try{for(Furniture f:prepared)anchors.add(a.session.createAnchor(origin.compose(f.testPose)));}catch(Exception e){for(Anchor anchor:anchors)anchor.detach();throw e;}
        for(int i=0;i<prepared.size();i++){Furniture f=prepared.get(i);f.anchor=anchors.get(i);f.testPose=null;items.add(f);cache.put(f.asset.id,f.asset);}pendingLayout=null;itemCount=items.size();selected=items.get(0);publish();reportGroupPosition("布局已恢复");
    }
    private Pose groupOrigin() throws java.io.IOException {
        if(items.isEmpty()||pendingLayout!=null)throw new java.io.IOException("请先把家具放到地面");
        if(pending!=null)throw new java.io.IOException("请先放下待添加的家具");
        if(trackedCamera==null)throw new java.io.IOException("请先恢复空间定位");
        for(Furniture item:items)if(!item.tracked())throw new java.io.IOException("请先恢复家具定位");
        return items.get(0).pose();
    }
    boolean isChoosingGroupPosition(){return choosingGroupPosition;}
    void toggleGroupPosition(){
        if(choosingGroupPosition){choosingGroupPosition=false;a.message("已取消重选，家具仍在原处");return;}
        try{groupOrigin();choosingGroupPosition=true;a.message("点击新的地面落点，整组家具会一起移动");}
        catch(Exception e){a.message(e.getMessage());}
    }
    private void moveGroupTo(Pose hit)throws Exception{
        Pose origin=groupOrigin();
        Pose translation=Pose.makeTranslation(hit.tx()-origin.tx(),hit.ty()-origin.ty(),hit.tz()-origin.tz());
        reanchorGroup(translation);
        choosingGroupPosition=false;
        reportGroupPosition("已移到新落点");
    }
    static Pose cameraDistanceShift(Pose origin,Pose camera,float metresCloser)throws java.io.IOException{
        float dx=camera.tx()-origin.tx(),dz=camera.tz()-origin.tz();
        float distance=(float)Math.hypot(dx,dz);
        if(distance<.001f)throw new java.io.IOException("请先离开第一件家具的位置，再调整距离");
        float step=metresCloser;
        if(step>0)step=Math.min(step,Math.max(0,distance-.45f));
        if(step==0)throw new java.io.IOException("第一件家具已很近，可改用重选落点");
        return Pose.makeTranslation(dx*step/distance,0,dz*step/distance);
    }
    void adjustGroupDistance(float metresCloser){
        try{
            Pose translation=cameraDistanceShift(groupOrigin(),trackedCamera,metresCloser);
            reanchorGroup(translation);
            choosingGroupPosition=false;
            reportGroupPosition(metresCloser>0?"已移近整组":"已移远整组");
        }catch(Exception e){a.message("整组移动失败："+e.getMessage());}
    }
    private void reportGroupPosition(String action){
        if(trackedCamera!=null){
            Pose origin=items.get(0).pose();
            double distance=Math.hypot(trackedCamera.tx()-origin.tx(),trackedCamera.tz()-origin.tz());
            action+=String.format(Locale.CHINA," · 第一件距手机约 %.1f 米（水平距离）",distance);
        }
        a.message(action);
        a.placementFeedback();
    }
    void rotateGroup(float radians){
        try{
            float[] t=groupOrigin().getTranslation();
            Pose rotation=Pose.makeRotation(0,(float)Math.sin(radians/2),0,(float)Math.cos(radians/2));
            Pose pivot=Pose.makeTranslation(t).compose(rotation).compose(Pose.makeTranslation(-t[0],-t[1],-t[2]));
            reanchorGroup(pivot);
        }catch(Exception e){a.message("整组旋转失败："+e.getMessage());}
    }
    private void reanchorGroup(Pose shift)throws Exception{
        sync();
        ArrayList<Anchor> anchors=new ArrayList<>();
        try{for(Furniture item:items)anchors.add(a.session.createAnchor(shift.compose(item.pose())));}
        catch(Exception e){for(Anchor anchor:anchors)anchor.detach();throw e;}
        for(int i=0;i<items.size();i++){
            Furniture item=items.get(i);
            item.detach();
            item.anchor=anchors.get(i);
        }
        publish();
    }
    static float intersect(float[] origin,float[] direction,float[] low,float[] high){float near=0,far=1;for(int k=0;k<3;k++){if(Math.abs(direction[k])<1e-8){if(origin[k]<low[k]||origin[k]>high[k])return Float.POSITIVE_INFINITY;continue;}float x=(low[k]-origin[k])/direction[k],y=(high[k]-origin[k])/direction[k];near=Math.max(near,Math.min(x,y));far=Math.min(far,Math.max(x,y));if(near>far)return Float.POSITIVE_INFINITY;}return near;}
    Furniture pick(float x,float y,MainActivity.ChairRenderer r){float[] vp=new float[16],inv=new float[16],p=new float[4],q=new float[4];Matrix.multiplyMM(vp,0,r.projection,0,r.view,0);if(!Matrix.invertM(inv,0,vp,0))return null;float nx=2*x/r.width-1,ny=1-2*y/r.height;Matrix.multiplyMV(p,0,inv,0,new float[]{nx,ny,-1,1},0);Matrix.multiplyMV(q,0,inv,0,new float[]{nx,ny,1,1},0);for(int i=0;i<3;i++){p[i]/=p[3];q[i]/=q[3];}p[3]=q[3]=1;Furniture best=null;float distance=Float.POSITIVE_INFINITY;
        for(Furniture f:items)if(f.tracked()){f.matrix();if(!Matrix.invertM(inv,0,f.model,0))continue;float[] lp=new float[4],lq=new float[4];Matrix.multiplyMV(lp,0,inv,0,p,0);Matrix.multiplyMV(lq,0,inv,0,q,0);float[] dir={lq[0]-lp[0],lq[1]-lp[1],lq[2]-lp[2]};float d=intersect(lp,dir,new float[]{-f.asset.width/2,0,-f.asset.depth/2},new float[]{f.asset.width/2,f.asset.height,f.asset.depth/2});if(d<distance){distance=d;best=f;}}return best;
    }
    void contextCreated(){transformTex=0;}
    void draw(MainActivity.ChairRenderer r){sync();ArrayList<Furniture> visible=new ArrayList<>();int n=0;for(Furniture f:items)if(f.tracked()){f.matrix();Matrix.multiplyMM(f.vm,0,r.view,0,f.model,0);visible.add(f);n+=f.asset.count;}drawCount=n;if(n==0)return;
        if(order.length!=n){order=new Integer[n];for(int i=0;i<n;i++)order[i]=i;sortDepth=new float[n];owners=new int[n];indices=new int[n];upload=ByteBuffer.allocateDirect(n*38*4).order(ByteOrder.nativeOrder()).asFloatBuffer();}
        float[] transforms=new float[visible.size()*28];int at=0;
        for(int k=0;k<visible.size();k++){Furniture f=visible.get(k);float[] vm=f.vm,inv=new float[16],eye=new float[4];Matrix.invertM(inv,0,vm,0);Matrix.multiplyMV(eye,0,inv,0,new float[]{0,0,0,1},0);System.arraycopy(vm,0,transforms,k*28,16);for(int c=0;c<3;c++){for(int j=0;j<3;j++)transforms[k*28+16+c*4+j]=f.asset.inverse[c*3+j];transforms[k*28+19+c*4]=eye[c];}
            for(int i=0;i<f.asset.count;i++){int j=i*37;float[] rows=f.asset.rows;sortDepth[at]=vm[2]*rows[j]+vm[6]*rows[j+1]+vm[10]*rows[j+2]+vm[14];owners[at]=k;indices[at]=j;at++;}}
        Arrays.sort(order,(l,h)->Float.compare(sortDepth[l],sortDepth[h]));upload.clear();for(int i:order){upload.put(visible.get(owners[i]).asset.rows,indices[i],37);upload.put(owners[i]);}upload.flip();
        if(transformTex==0){int[] id=new int[1];GLES30.glGenTextures(1,id,0);transformTex=id[0];}GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,transformTex);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA32F,7,visible.size(),0,GLES30.GL_RGBA,GLES30.GL_FLOAT,r.buffer(transforms));GLES30.glActiveTexture(GLES30.GL_TEXTURE0);r.drawBatch(upload,38,n,true);frames++;
        Furniture f=selected;if(f!=null&&f.tracked()){System.arraycopy(f.model,0,r.model,0,16);System.arraycopy(f.vm,0,r.vm,0,16);if(a.showSizeGuide)r.drawSizeGuide();if(a.showGroundGuide)r.drawGroundGuide();}
    }
    void preview(MainActivity.ChairRenderer r){sync();Furniture f=selected;if(f==null)return;RoomAsset asset=f.asset;r.rows=asset.rows;r.count=asset.count;System.arraycopy(asset.inverse,0,r.rawInverse,0,9);r.depth=new float[r.count];r.order=new Integer[r.count];for(int i=0;i<r.count;i++)r.order[i]=i;r.upload=ByteBuffer.allocateDirect(r.rows.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();}
}
