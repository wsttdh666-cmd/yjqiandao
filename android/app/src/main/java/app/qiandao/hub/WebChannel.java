package app.qiandao.hub;

import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;

// 共用给 PG 和 Gemini 网页版：把粘贴的 Cookie 塞进 WebView，返回用于退出时清除的名单
final class WebChannel {
    private WebChannel() {
    }

    static JSONArray cookies(String text) throws Exception {
        JSONArray names = new JSONArray();
        CookieManager cm = CookieManager.getInstance();
        text = text.trim().replaceFirst("(?i)^cookie:\\s*", "");
        if (text.startsWith("[")) {
            JSONArray list = new JSONArray(text);
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i);
                String domain = c.optString("domain").replaceFirst("^\\.", "");
                put(cm, names, "https://" + domain, c.getString("name"), c.getString("value"), c.optBoolean("hostOnly") ? null : domain);
            }
        } else {
            for (String pair : text.split(";\\s*")) {
                int i = pair.indexOf('=');
                if (i <= 0) continue;
                String name = pair.substring(0, i).trim();
                put(cm, names, "https://www.google.com", name, pair.substring(i + 1).trim(), name.startsWith("__Host-") ? null : "google.com");
            }
        }
        if (!names.toString().contains("SAPISID")) throw new IllegalStateException("里面没有谷歌的登录信息，要在登录着谷歌的页面上导出");
        cm.flush();
        return names;
    }

    static void clear(JSONArray names) {
        CookieManager cm = CookieManager.getInstance();
        for (int i = 0; names != null && i < names.length(); i++) {
            try {
                cm.setCookie(names.getJSONArray(i).getString(0), names.getJSONArray(i).getString(1) + "; Max-Age=0");
            } catch (Exception ignored) {
            }
        }
        cm.flush();
    }

    private static void put(CookieManager cm, JSONArray names, String url, String name, String value, String domain) throws Exception {
        String attrs = "; Path=/; Secure" + (domain == null ? "" : "; Domain=." + domain);
        cm.setCookie(url, name + "=" + value + attrs + "; Max-Age=34560000");
        names.put(new JSONArray().put(url).put(name + "=" + attrs));
    }
}
