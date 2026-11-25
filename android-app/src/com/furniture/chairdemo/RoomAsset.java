package com.furniture.chairdemo;
import java.io.*;
import org.json.*;

/** Immutable Gaussian data, shared by duplicate furniture instances. */
final class RoomAsset {
    final String id;final JSONObject metadata;final float[] rows,inverse;final int count;
    final float width,depth,height;volatile float userWidth,userDepth,userHeight,contact;volatile boolean confirmed;volatile String sizeMode="height";
    RoomAsset(MainActivity a,String id)throws Exception{this.id=id;metadata=ModelStore.metadata(a,id);PackedModel p;try(InputStream in=ModelStore.openPacked(a,id)){p=PackedModel.read(in);}rows=p.rows;inverse=p.inverse;count=p.count;JSONObject d=ModelStore.dimensions(a,id);width=(float)d.getDouble("width_m");depth=(float)d.getDouble("depth_m");height=(float)d.getDouble("height_m");
        android.content.SharedPreferences prefs=ModelStore.BUILTIN.equals(id)?a.getPreferences(0):a.getSharedPreferences("model-size-"+id,0);float h=(float)d.optDouble("default_height_m",height);userHeight=prefs.getFloat("height",h);userWidth=prefs.getFloat("width",width*userHeight/height);userDepth=prefs.getFloat("depth",depth*userHeight/height);contact=prefs.getFloat("contactOffsetM",0);confirmed=prefs.getBoolean("dimensionsConfirmed","calibrated_furniture".equals(metadata.optString("kind")));sizeMode=prefs.getString("sizeMode","height");
    }
}
