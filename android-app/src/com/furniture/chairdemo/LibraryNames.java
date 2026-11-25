package com.furniture.chairdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** User-visible titles are local metadata. File identities and upload contents stay stable. */
final class LibraryNames {
    static String validate(String input, boolean allowBlank) throws IOException {
        String name = input.replaceAll("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$", "");
        if (name.isEmpty() && allowBlank) return null;
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 40 || name.matches("(?s).*[\\p{Cc}\\p{Zl}\\p{Zp}].*")) {
            throw new IOException("请输入 1 至 40 个字符的单行名称");
        }
        return name;
    }

    private static SharedPreferences names(Context context) {
        return context.getSharedPreferences("library-names", Context.MODE_PRIVATE);
    }

    private static void save(Context context, String key, String name) throws IOException {
        if (!names(context).edit().putString(key, validate(name, false)).commit()) {
            throw new IOException("名称未能保存，请重试");
        }
    }

    private static String datedTitle(String prefix, String id) {
        try {
            long timestamp = Long.parseLong(id.split("-")[1]);
            return prefix + " · " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(timestamp));
        } catch (NumberFormatException ignored) {
            return prefix;
        }
    }

    static String capture(Context context, String id) {
        if (!id.matches("scan-\\d+-[a-f0-9]{8}")) return "拍摄记录";
        return names(context).getString("capture/" + id, datedTitle("拍摄", id));
    }

    static void renameCapture(Context context, String id, String name) throws IOException {
        if (!id.matches("scan-\\d+-[a-f0-9]{8}") || !new File(context.getFilesDir(), "captures/" + id + "/capture.json").isFile()) {
            throw new IOException("找不到这份拍摄记录");
        }
        save(context, "capture/" + id, name);
    }

    static String scene(Context context, String taskId, String captureId, String source) {
        if (!taskId.matches("probe-\\d+-1200")) return "三维场景";
        String namedCapture = names(context).getString("capture/" + captureId, null);
        String fallback = datedTitle("computer".equals(source) ? "电脑场景" : "手机场景", taskId);
        return names(context).getString("scene/" + taskId, namedCapture == null ? fallback : namedCapture);
    }

    static void renameScene(Context context, File task, String name) throws IOException {
        if (!task.getName().matches("probe-\\d+-1200") || LibraryStore.taskDeleted(context, task)
                || !new File(task, "integration-result.json").isFile()) {
            throw new IOException("找不到这个三维场景");
        }
        save(context, "scene/" + task.getName(), name);
    }

    private static String furnitureKey(JSONObject model) {
        return "furniture/" + model.optString("calibration_source_id", model.optString("id"));
    }

    static String furniture(Context context, JSONObject model) {
        return names(context).getString(furnitureKey(model), model.optString("name", "家具"));
    }

    static void renameFurniture(Context context, JSONObject model, String name) throws IOException {
        String id = model.optString("id");
        if (!id.matches("[a-f0-9]{64}") || LibraryStore.modelDeleted(context, id)
                || !new File(context.getFilesDir(), "models/" + id + "/model.json").isFile()) {
            throw new IOException("找不到这件家具");
        }
        save(context, furnitureKey(model), name);
    }
}
