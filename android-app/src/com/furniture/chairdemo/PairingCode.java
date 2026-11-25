package com.furniture.chairdemo;
import java.io.IOException;
import org.json.JSONObject;
/** Strict local pairing payload, never a URL to open in a browser. */
final class PairingCode {
    static String[] parse(String value)throws Exception{
        if(value==null||value.length()>1024)throw new IOException("二维码内容无效");
        JSONObject j=new JSONObject(value);
        if(!"furniture-pair".equals(j.optString("type"))||j.optInt("version")!=1)throw new IOException("请扫描本项目电脑页面上的连接二维码");
        String url=ComputerActivity.normalizeUrl(j.getString("url")),code=j.getString("code");
        if(!code.matches("[0-9]{8}"))throw new IOException("二维码中的连接码无效");
        return new String[]{url,code};
    }
}
