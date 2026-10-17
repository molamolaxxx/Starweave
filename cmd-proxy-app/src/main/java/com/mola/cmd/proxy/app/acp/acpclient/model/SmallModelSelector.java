package com.mola.cmd.proxy.app.acp.acpclient.model;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative name-based selection; catalogs do not expose comparable size or price. */
public final class SmallModelSelector {
    private static final Pattern SMALL = Pattern.compile(
            "(?:^|[^a-z])(nano|mini|haiku|flash|lite|small|luna)(?=$|[^a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_TEXT = Pattern.compile("embed|rerank|image|vision|audio|tts|whisper", Pattern.CASE_INSENSITIVE);

    private SmallModelSelector() {}

    public static boolean isSmallModel(String id) {
        return id != null && SMALL.matcher(id).find() && !NON_TEXT.matcher(id).find();
    }

    public static String select(JSONArray models) {
        String selected = null;
        if (models == null) return null;
        for (Object value : models) {
            if (!(value instanceof JSONObject)) continue;
            JSONObject model = (JSONObject) value;
            String id = model.getString("id"), source = model.getString("source");
            if (id == null || id.trim().isEmpty() || source == null
                    || "history".equals(source) || "configured".equals(source)) continue;
            if (!isSmallModel(id + " " + model.getString("name"))) continue;
            if (selected == null || id.toLowerCase(Locale.ROOT).compareTo(selected.toLowerCase(Locale.ROOT)) < 0) selected = id;
        }
        return selected;
    }
}
