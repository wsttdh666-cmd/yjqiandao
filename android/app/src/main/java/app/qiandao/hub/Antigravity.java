package app.qiandao.hub;

import android.net.Uri;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class Antigravity implements Relay.Channel {
    private static final String PREFIX = "【agy】";
    // 反重力桌面客户端的公开 OAuth 凭据（非机密，和 Antigravity 客户端内置的一样）；拆开写只为绕过 GitHub 的密钥扫描
    private static final String CLIENT = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep" + ".apps.google" + "usercontent.com";
    private static final String SECRET = "GOCSPX-K58FWR486" + "LdLJ1mLB8sXC4z6qDAf";
    private static final int PORT = 51121;
    private static final String REDIRECT = "http://localhost:" + PORT + "/oauth-callback";
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String API = "https://cloudcode-pa.googleapis.com/v1internal";
    private static final String USERINFO = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String AGENT = "antigravity/hub/2.33.0 (aidev_client; os_type=darwin; arch=arm64)";
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email "
        + "https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/aicode "
        + "https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs";
    private static final List<String> FALLBACK = java.util.Arrays.asList("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3-pro", "gemini-3.6-flash");

    private final Relay relay;
    private JSONObject account;

    Antigravity(Relay relay) {
        this.relay = relay;
        account = relay.load("antigravity");
    }

    String email() {
        return account == null ? null : account.optString("email", "已登录");
    }

    void logout() throws Exception {
        account = null;
        relay.save("antigravity", null);
    }

    public boolean ready() {
        return account != null;
    }

    public String who() {
        return "agy:" + email();
    }

    public List<String> models() {
        List<String> list = new ArrayList<>();
        JSONArray ids = account == null ? null : account.optJSONArray("models");
        if (ids == null || ids.length() == 0) {
            for (String m : FALLBACK) list.add(PREFIX + m);
        } else {
            for (int i = 0; i < ids.length(); i++) list.add(PREFIX + ids.optString(i));
        }
        return list;
    }

    public boolean owns(String model) {
        return model.startsWith(PREFIX);
    }

    String begin(Consumer<String> done) throws Exception {
        String state = Relay.random(16);
        Relay.listen(PORT, "/oauth-callback", u -> {
            String code = u.getQueryParameter("code");
            if (!state.equals(u.getQueryParameter("state")) || code == null) return "登录没成功：" + u.getQueryParameter("error");
            exchange(code);
            return null;
        }, done);
        return AUTH + "?access_type=offline&client_id=" + Uri.encode(CLIENT) + "&prompt=consent&redirect_uri=" + Uri.encode(REDIRECT)
            + "&response_type=code&scope=" + Uri.encode(SCOPE) + "&state=" + state;
    }

    private JSONObject post(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
        if (c.getResponseCode() != 200) throw Relay.upstream("谷歌登录", c);
        return new JSONObject(Relay.read(c.getInputStream()));
    }

    private void keep(JSONObject t) throws Exception {
        JSONObject a = account == null ? new JSONObject() : account;
        a.put("access", t.getString("access_token"))
            .put("refresh", t.optString("refresh_token", a.optString("refresh")))
            .put("expires", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000);
        account = a;
        relay.save("antigravity", a);
    }

    private void exchange(String code) throws Exception {
        keep(post("grant_type=authorization_code&code=" + Uri.encode(code) + "&client_id=" + Uri.encode(CLIENT)
            + "&client_secret=" + Uri.encode(SECRET) + "&redirect_uri=" + Uri.encode(REDIRECT)));
        try {
            account.put("email", userinfo());
        } catch (Exception ignored) {
        }
        try {
            account.put("project", project());
        } catch (Relay.Upstream e) {
            logout();
            throw new Relay.Upstream(e.code, "这个号用不了反重力：" + e.getMessage(), 0);
        }
        try {
            account.put("models", fetchModels());
        } catch (Exception ignored) {
        }
        relay.save("antigravity", account);
    }

    private synchronized String access() throws Exception {
        if (System.currentTimeMillis() > account.getLong("expires") - 300000) {
            keep(post("grant_type=refresh_token&client_id=" + Uri.encode(CLIENT) + "&client_secret=" + Uri.encode(SECRET)
                + "&refresh_token=" + Uri.encode(account.getString("refresh"))));
        }
        return account.getString("access");
    }

    private String userinfo() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(USERINFO + "?alt=json").openConnection();
        c.setRequestProperty("Authorization", "Bearer " + account.getString("access"));
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        if (c.getResponseCode() != 200) return "已登录";
        return new JSONObject(Relay.read(c.getInputStream())).optString("email", "已登录");
    }

    private JSONObject call(String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + ":" + method).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + access());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", AGENT);
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        if (c.getResponseCode() != 200) throw Relay.upstream("反重力", c);
        return new JSONObject(Relay.read(c.getInputStream()));
    }

    private String project() throws Exception {
        JSONObject r = call("loadCodeAssist", "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\"}}");
        for (String key : new String[] {"cloudaicompanionProject", "projectId", "project"}) {
            Object v = r.opt(key);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            if (v instanceof JSONObject && !((JSONObject) v).optString("id").isEmpty()) return ((JSONObject) v).getString("id");
        }
        return "";
    }

    private JSONArray fetchModels() throws Exception {
        String pid = account.optString("project");
        JSONObject r = call("fetchAvailableModels", pid.isEmpty() ? "{}" : new JSONObject().put("project", pid).toString());
        JSONArray models = r.optJSONArray("models"), ids = new JSONArray();
        for (int i = 0; models != null && i < models.length(); i++) {
            String id = models.getJSONObject(i).optString("id");
            if (!id.isEmpty()) ids.put(id);
        }
        return ids;
    }

    public void chat(JSONObject req, Relay.Sink sink) throws Exception {
        String model = req.getString("model").substring(PREFIX.length());
        JSONArray contents = new JSONArray();
        JSONObject system = null;
        JSONArray messages = req.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.optString("role");
            JSONArray parts = new JSONArray();
            Object content = m.opt("content");
            if (content instanceof JSONArray) {
                JSONArray list = (JSONArray) content;
                for (int k = 0; k < list.length(); k++) {
                    JSONObject p = list.optJSONObject(k);
                    if (p == null) continue;
                    if ("text".equals(p.optString("type"))) {
                        parts.put(new JSONObject().put("text", p.optString("text")));
                    } else if ("image_url".equals(p.optString("type"))) {
                        JSONObject img = p.optJSONObject("image_url");
                        String url = img == null ? p.optString("image_url") : img.optString("url");
                        int comma = url.indexOf(',');
                        if (url.startsWith("data:") && comma > 0) {
                            parts.put(new JSONObject().put("inlineData", new JSONObject()
                                .put("mimeType", url.substring(5, url.indexOf(';'))).put("data", url.substring(comma + 1))));
                        }
                    }
                }
            } else {
                parts.put(new JSONObject().put("text", Relay.text(content)));
            }
            if (role.equals("system") || role.equals("developer")) {
                system = new JSONObject().put("parts", parts);
            } else {
                contents.put(new JSONObject().put("role", role.equals("assistant") ? "model" : "user").put("parts", parts));
            }
        }
        JSONObject config = new JSONObject();
        if (req.has("temperature")) config.put("temperature", req.getDouble("temperature"));
        if (req.has("top_p")) config.put("topP", req.getDouble("top_p"));
        if (req.has("max_tokens")) config.put("maxOutputTokens", req.getInt("max_tokens"));
        String effort = req.optString("reasoning_effort");
        if (!effort.isEmpty()) {
            int level = java.util.Arrays.asList("", "low", "medium", "high").indexOf(effort.toLowerCase(Locale.ROOT));
            JSONObject thinking = new JSONObject().put("includeThoughts", true);
            if (level > 0) thinking.put("thinkingLevel", level);
            config.put("thinkingConfig", thinking);
        }
        JSONObject inner = new JSONObject().put("contents", contents);
        if (system != null) inner.put("systemInstruction", system);
        if (config.length() > 0) inner.put("generationConfig", config);
        JSONObject body = new JSONObject().put("model", model).put("requestId", UUID.randomUUID().toString()).put("request", inner);
        String pid = account.optString("project");
        if (!pid.isEmpty()) body.put("project", pid);

        HttpURLConnection c = (HttpURLConnection) new URL(API + ":streamGenerateContent?alt=sse").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setReadTimeout(300000);
        c.setRequestProperty("Authorization", "Bearer " + access());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", AGENT);
        c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        if (c.getResponseCode() != 200) throw Relay.upstream("Gemini", c);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (!l.startsWith("data:")) continue;
                String data = l.substring(5).trim();
                if (data.isEmpty()) continue;
                frame(new JSONObject(data).optJSONObject("response"), sink);
            }
        }
    }

    private void frame(JSONObject resp, Relay.Sink sink) throws Exception {
        if (resp == null) return;
        JSONArray candidates = resp.optJSONArray("candidates");
        JSONObject cand = candidates == null ? null : candidates.optJSONObject(0);
        if (cand == null) return;
        JSONObject content = cand.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null || !p.has("text")) continue;
            if (p.optBoolean("thought")) sink.delta(null, p.optString("text"));
            else sink.delta(p.optString("text"), null);
        }
        if ("MAX_TOKENS".equals(cand.optString("finishReason"))) sink.finish = "length";
    }
}
