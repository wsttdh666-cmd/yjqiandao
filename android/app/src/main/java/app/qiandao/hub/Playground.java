package app.qiandao.hub;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class Playground implements Relay.Channel {
    private static final String PREFIX = "【pg】";
    private static final String PAGE = "https://aistudio.google.com/prompts/new_chat?temporary=true&model=gemini-3.8-flash";

    private final Relay relay;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, LinkedBlockingQueue<JSONArray>> pending = new ConcurrentHashMap<>();
    private final Map<String, JSONArray> rows = new ConcurrentHashMap<>();
    private WebView web;
    private volatile boolean ready;
    private volatile String broken;
    private boolean started;
    private Consumer<String> waiting;

    Playground(Relay relay, Context ctx) {
        this.relay = relay;
        this.ctx = ctx;
        reload();
    }

    public void reload() {
        try {
            catalog(new JSONArray(relay.prefs.getString("pgModels", "[[]]")));
        } catch (Exception ignored) {
        }
        main.post(() -> {
            close();
            if (relay.prefs.contains("pgCookies")) open();
        });
    }

    // 每个号一个 Cookie 盒子，最早登录的那个号用老名字
    private String profile() {
        return relay.prefs.getString("pgProfile", "pg");
    }

    String email() {
        return relay.prefs.contains("pgCookies") ? relay.prefs.getString("pgEmail", "已登录") : null;
    }

    // 界面上显示的状态：没准备好就说原因，不写「已登录」
    String status() {
        if (!relay.prefs.contains("pgCookies")) return null;
        return broken != null ? "没连上：" + broken : ready ? email() : "正在连…";
    }

    public boolean ready() {
        return relay.prefs.contains("pgCookies");
    }

    public String who() {
        return "pg:" + email();
    }

    public List<String> models() {
        List<String> list = new ArrayList<>();
        for (String id : rows.keySet()) list.add(PREFIX + id);
        Collections.sort(list);
        return list;
    }

    public boolean owns(String model) {
        return model.startsWith(PREFIX);
    }

    private static boolean has(JSONArray caps, int code) {
        for (int i = 0; caps != null && i < caps.length(); i++) if (caps.optInt(i) == code) return true;
        return false;
    }

    private void catalog(JSONArray root) {
        JSONArray list = root.optJSONArray(0);
        rows.clear();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONArray row = list.optJSONArray(i);
            if (row == null) continue;
            String id = row.optString(0).replaceFirst("^models/", "");
            // 只留能聊天的 Gemini / Gemma，滤掉语音、转录、画图、机器人、实时翻译这些用不上的
            if (!(id.startsWith("gemini") || id.startsWith("gemma"))) continue;
            if (id.matches(".*(-tts|transcribe|image|nano-banana|robotics|-live|imagen|veo|embed).*")) continue;
            rows.put(id, row);
        }
    }

    void login(String text, Consumer<String> done) {
        main.post(() -> {
            JSONArray names;
            try {
                names = WebChannel.cookies(text, WebChannel.jar(profile()));
            } catch (IllegalStateException e) {
                done.accept(e.getMessage());
                return;
            } catch (Exception e) {
                done.accept("Cookie 格式认不出来，用 Cookie-Editor 导出的 JSON 或请求头里的 Cookie 那一行");
                return;
            }
            relay.prefs.edit().putString("pgCookies", names.toString()).apply();
            waiting = done;
            open();
        });
    }

    void logout() {
        String names = relay.prefs.getString("pgCookies", "[]");
        String box = profile();
        relay.prefs.edit().remove("pgCookies").remove("pgEmail").remove("pgModels").remove("pgUser").commit();
        rows.clear();
        try {
            relay.release("pg", box, new JSONArray(names));
        } catch (Exception ignored) {
        }
        main.post(this::close);
    }

    void close() {
        if (web != null) web.destroy();
        web = null;
        ready = false;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void open() {
        close();
        broken = null;
        started = false;
        String script;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.pg)) {
            script = Relay.read(in);
        } catch (Exception e) {
            return;
        }
        web = new WebView(ctx);
        WebChannel.use(web, profile());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setOffscreenPreRaster(true);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString().replace("; wv", "").replaceFirst("Version/\\S+ ", ""));
        web.layout(0, 0, 1080, 2000);
        web.resumeTimers();
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void post(String json) {
                try {
                    message(new JSONArray(json));
                } catch (Exception ignored) {
                }
            }
        }, "PgBridge");
        web.setWebViewClient(new WebViewClient() {
            // 网没连上（比如刚开时被系统冻住）：别在错误页上跑脚本，30 秒后重开
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (view != web || !req.isForMainFrame()) return;
                started = true;
                fail("连不上谷歌，30 秒后自动再试");
                main.postDelayed(() -> {
                    if (web == view) open();
                }, 30000);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != web || started || !"aistudio.google.com".equals(Uri.parse(url).getHost())) return;
                started = true;
                view.evaluateJavascript(script + ";__pg.start()", null);
            }
        });
        WebView mine = web;
        main.postDelayed(() -> {
            if (web != mine || ready || broken != null) return;
            fail(started ? "AI Studio 页面太久没准备好" : "谷歌没认这份 Cookie，可能过期了，重新导出一份再登录");
        }, 90000);
        web.loadUrl(PAGE + "&authuser=" + relay.prefs.getString("pgUser", "0"));
    }

    private void fail(String why) {
        broken = why;
        if (waiting != null) {
            waiting.accept(why);
            waiting = null;
        }
    }

    private void message(JSONArray m) throws Exception {
        String type = m.getString(0);
        if (type.equals("ready")) {
            String root = m.getString(2);
            catalog(new JSONArray(root));
            relay.prefs.edit().putString("pgModels", root).putString("pgEmail", m.getString(1).isEmpty() ? "已登录" : m.getString(1)).apply();
            ready = true;
            main.post(() -> {
                if (waiting != null) waiting.accept(null);
                waiting = null;
            });
        } else if (type.equals("broken")) {
            main.post(() -> fail("AI Studio 页面没准备好：" + m.optString(1)));
        } else {
            LinkedBlockingQueue<JSONArray> q = pending.get(m.getString(1));
            if (q != null) q.add(m);
        }
    }

    private JSONArray wire(JSONObject req, String id, JSONArray row) throws Exception {
        JSONArray contents = new JSONArray(), system = new JSONArray(), messages = req.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.optString("role");
            JSONArray parts = new JSONArray();
            Object content = m.opt("content");
            JSONArray list = content instanceof JSONArray ? (JSONArray) content : new JSONArray().put(new JSONObject().put("type", "text").put("text", Relay.text(content)));
            for (int k = 0; k < list.length(); k++) {
                JSONObject p = list.optJSONObject(k);
                if (p == null) continue;
                if ("text".equals(p.optString("type")) && !p.optString("text").isEmpty()) {
                    parts.put(new JSONArray().put(JSONObject.NULL).put(p.optString("text")));
                } else if ("image_url".equals(p.optString("type"))) {
                    JSONObject img = p.optJSONObject("image_url");
                    String url = img == null ? p.optString("image_url") : img.optString("url");
                    int comma = url.indexOf(',');
                    if (url.startsWith("data:") && comma > 0) {
                        parts.put(new JSONArray().put(JSONObject.NULL).put(JSONObject.NULL)
                            .put(new JSONArray().put(url.substring(5, url.indexOf(';'))).put(url.substring(comma + 1))));
                    }
                }
            }
            if (parts.length() == 0) continue;
            if (role.equals("system") || role.equals("developer")) {
                for (int k = 0; k < parts.length(); k++) system.put(parts.get(k));
            } else {
                contents.put(new JSONArray().put(parts).put(role.equals("assistant") ? "model" : "user"));
            }
        }
        JSONArray safety = new JSONArray();
        for (int c = 7; c <= 10; c++) safety.put(new JSONArray().put(JSONObject.NULL).put(JSONObject.NULL).put(c).put(5));
        JSONArray config = new JSONArray();
        Object stop = req.opt("stop");
        if (stop instanceof String) config.put(1, new JSONArray().put(stop));
        else if (stop instanceof JSONArray) config.put(1, stop);
        config.put(3, req.optInt("max_tokens", req.optInt("max_completion_tokens", row.optInt(6, 65536))));
        if (req.has("temperature")) config.put(4, req.getDouble("temperature"));
        if (req.has("top_p")) config.put(5, req.getDouble("top_p"));
        if (req.has("top_k")) config.put(6, req.getInt("top_k"));
        config.put(13, 1);
        JSONArray caps = row.optJSONArray(64);
        if (has(caps, 25)) {
            JSONArray thinking = new JSONArray().put(1);
            int level = Arrays.asList("", "low", "medium", "high", "minimal").indexOf(req.optString("reasoning_effort").toLowerCase(Locale.ROOT));
            if (level > 0 && has(caps, 52)) thinking.put(3, level);
            config.put(16, thinking);
        }
        JSONArray w = new JSONArray().put("models/" + id).put(contents).put(safety).put(config).put(JSONObject.NULL)
            .put(system.length() > 0 ? new JSONArray().put(system).put("user") : JSONObject.NULL);
        w.put(10, 1);
        w.put(13, new JSONArray().put(new JSONArray().put(JSONObject.NULL).put(JSONObject.NULL).put(TimeZone.getDefault().getID())));
        return w;
    }

    public void chat(JSONObject req, Relay.Sink sink) throws Exception {
        String id = req.getString("model").substring(PREFIX.length());
        JSONArray row = rows.get(id);
        if (row == null) throw new Relay.Upstream(404, "这个号用不了 " + id, 0);
        for (int i = 0; !ready && i < 120; i++) {
            if (broken != null) throw new Relay.Upstream(503, broken, 0);
            Thread.sleep(500);
        }
        if (!ready) throw new Relay.Upstream(503, "AI Studio 页面还没准备好，稍后再试", 0);
        String key = Relay.random(8);
        LinkedBlockingQueue<JSONArray> q = new LinkedBlockingQueue<>();
        pending.put(key, q);
        String call = "__pg.run(" + JSONObject.quote(key) + "," + wire(req, id, row) + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(call, null);
        });
        try {
            Stream stream = new Stream(sink);
            while (true) {
                JSONArray m = q.poll(5, TimeUnit.MINUTES);
                if (m == null) throw new Relay.Upstream(504, "pg 等了 5 分钟没回应", 0);
                switch (m.getString(0)) {
                    case "chunk":
                        stream.feed(m.getString(2));
                        break;
                    case "end":
                        stream.end();
                        return;
                    default:
                        int code = m.optInt(2, 502);
                        if (code == 401 || code == 403) main.post(this::open);
                        throw new Relay.Upstream(code, "pg 返回错误（" + code + "）：" + errorText(m.optString(3)), 0);
                }
            }
        } finally {
            pending.remove(key);
            // 酒馆取消了也要叫停网页里那个还在等谷歌的请求
            main.post(() -> {
                if (web != null) web.evaluateJavascript("__pg.stop(" + JSONObject.quote(key) + ")", null);
            });
        }
    }

    private static String errorText(String body) {
        try {
            JSONArray a = new JSONArray(body.replaceFirst("^\\)\\]\\}'\\s*", ""));
            JSONArray status = a.optJSONArray(1);
            if (status != null) return status.optString(1, body);
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject(body).getJSONObject("error").optString("message", body);
        } catch (Exception ignored) {
        }
        return body;
    }

    static final class Stream {
        private final Relay.Sink sink;
        private final StringBuilder buf = new StringBuilder();
        private int pos, depth, start, child;
        private boolean quoted, escaped, said;
        private int finish;
        private JSONArray status;

        Stream(Relay.Sink sink) {
            this.sink = sink;
        }

        void feed(String text) throws Exception {
            buf.append(text);
            for (; pos < buf.length(); pos++) {
                char c = buf.charAt(pos);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') quoted = false;
                } else if (c == '"') {
                    quoted = true;
                } else if (c == '[') {
                    depth++;
                    if (depth == 2) child++;
                    if (depth == 3 && child == 1 || depth == 2 && child == 2) start = pos;
                } else if (c == ']' && depth > 0) {
                    if (depth == 3 && child == 1) frame(new JSONArray(buf.substring(start, pos + 1)));
                    if (depth == 2 && child == 2) status = new JSONArray(buf.substring(start, pos + 1));
                    depth--;
                }
            }
        }

        private void frame(JSONArray f) throws Exception {
            JSONArray candidate = f.optJSONArray(0) == null ? null : f.getJSONArray(0).optJSONArray(0);
            if (candidate == null) return;
            JSONArray content = candidate.optJSONArray(0), parts = content == null ? null : content.optJSONArray(0);
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONArray p = parts.optJSONArray(i);
                if (p == null || !(p.opt(1) instanceof String) || p.getString(1).isEmpty()) continue;
                boolean thought = p.optInt(12) == 1 || p.optBoolean(12);
                if (thought) sink.delta(null, p.getString(1));
                else sink.delta(p.getString(1), null);
                said = true;
            }
            if (candidate.optInt(1) > 0) finish = candidate.optInt(1);
        }

        void end() throws Exception {
            if (status != null && status.optInt(0) != 0) {
                int code = status.optInt(0);
                int http = code == 8 ? 429 : code == 7 ? 403 : code == 16 ? 401 : code == 3 ? 400 : 502;
                throw new Relay.Upstream(http, "pg 出错：" + status.optString(1), 0);
            }
            if (finish == 2) sink.finish = "length";
            else if (finish > 2 && !said) throw new Relay.Upstream(502, "谷歌拦下了这次回复（原因代码 " + finish + "）", 0);
        }
    }
}
