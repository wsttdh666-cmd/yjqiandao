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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

// Gemini 网页版（gemini.google.com）页面转接：粘贴 Cookie，后台网页里直接操作输入框、发送、读回复
final class GeminiWeb implements Relay.Channel {
    private static final String PREFIX = "【网页】";
    private static final String MODEL = PREFIX + "gemini";
    private static final String PAGE = "https://gemini.google.com/app";

    private final Relay relay;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, LinkedBlockingQueue<JSONArray>> pending = new ConcurrentHashMap<>();
    private WebView web;
    private volatile boolean ready, started;
    private volatile String broken;
    private Consumer<String> waiting;

    GeminiWeb(Relay relay, Context ctx) {
        this.relay = relay;
        this.ctx = ctx;
        if (relay.prefs.contains("webCookies")) main.post(this::open);
    }

    String email() {
        return relay.prefs.contains("webCookies") ? relay.prefs.getString("webEmail", "已登录") : null;
    }

    // 界面上显示的状态：没准备好就说原因，不写「已登录」
    String status() {
        if (!relay.prefs.contains("webCookies")) return null;
        return broken != null ? "没连上：" + broken : ready ? email() : "正在连…";
    }

    public boolean ready() {
        return relay.prefs.contains("webCookies");
    }

    public String who() {
        return "web:" + email();
    }

    public List<String> models() {
        return Arrays.asList(MODEL);
    }

    public boolean owns(String model) {
        return model.equals(MODEL);
    }

    void login(String text, Consumer<String> done) {
        main.post(() -> {
            JSONArray names;
            try {
                names = WebChannel.cookies(text, WebChannel.jar("web"));
            } catch (IllegalStateException e) {
                done.accept(e.getMessage());
                return;
            } catch (Exception e) {
                done.accept("Cookie 格式认不出来，用 Cookie-Editor 导出的 JSON 或请求头里的 Cookie 那一行");
                return;
            }
            relay.prefs.edit().putString("webCookies", names.toString()).apply();
            waiting = done;
            open();
        });
    }

    void logout() {
        String names = relay.prefs.getString("webCookies", "[]");
        relay.prefs.edit().remove("webCookies").remove("webEmail").apply();
        main.post(() -> {
            try {
                WebChannel.clear(new JSONArray(names), WebChannel.jar("web"));
            } catch (Exception ignored) {
            }
            close();
        });
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
        try (InputStream in = ctx.getResources().openRawResource(R.raw.web)) {
            script = Relay.read(in);
        } catch (Exception e) {
            return;
        }
        web = new WebView(ctx);
        WebChannel.use(web, "web");
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
        }, "WebBridge");
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
                if (view != web || started || !"gemini.google.com".equals(Uri.parse(url).getHost())) return;
                started = true;
                view.evaluateJavascript(script + ";__web.start()", null);
            }
        });
        WebView mine = web;
        main.postDelayed(() -> {
            if (web != mine || ready || broken != null) return;
            fail(started ? "Gemini 网页太久没准备好" : "谷歌没认这份 Cookie，可能过期了，重新导出一份再登录");
        }, 90000);
        web.loadUrl(PAGE);
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
            ready = true;
            main.post(() -> {
                if (waiting != null) waiting.accept(null);
                waiting = null;
            });
        } else if (type.equals("broken")) {
            main.post(() -> fail("Gemini 网页没准备好：" + m.optString(1)));
        } else {
            LinkedBlockingQueue<JSONArray> q = pending.get(m.getString(1));
            if (q != null) q.add(m);
        }
    }

    private static String flatten(JSONObject req) {
        StringBuilder sb = new StringBuilder();
        JSONArray messages = req.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            String role = msg.optString("role"), text = Relay.text(msg.opt("content")).trim();
            if (text.isEmpty()) continue;
            String tag = role.equals("assistant") ? "助手" : role.equals("system") || role.equals("developer") ? "设定" : "用户";
            sb.append("【").append(tag).append("】\n").append(text).append("\n\n");
        }
        return sb.toString().trim();
    }

    public void chat(JSONObject req, Relay.Sink sink) throws Exception {
        for (int i = 0; !ready && i < 120; i++) {
            if (broken != null) throw new Relay.Upstream(503, broken, 0);
            Thread.sleep(500);
        }
        if (!ready) throw new Relay.Upstream(503, "Gemini 网页还没准备好，稍后再试", 0);
        String key = Relay.random(8);
        LinkedBlockingQueue<JSONArray> q = new LinkedBlockingQueue<>();
        pending.put(key, q);
        String call = "__web.run(" + JSONObject.quote(key) + "," + JSONObject.quote(flatten(req)) + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(call, null);
        });
        try {
            while (true) {
                JSONArray m = q.poll(7, TimeUnit.MINUTES);
                if (m == null) throw new Relay.Upstream(504, "网页版等了 7 分钟没回应", 0);
                switch (m.getString(0)) {
                    case "chunk":
                        sink.delta(m.getString(2), null);
                        break;
                    case "end":
                        return;
                    default:
                        throw new Relay.Upstream(m.optInt(2, 502), "网页版出错：" + m.optString(3), 0);
                }
            }
        } finally {
            pending.remove(key);
            main.post(() -> {
                if (web != null) web.evaluateJavascript("__web.stop(" + JSONObject.quote(key) + ")", null);
            });
        }
    }
}
