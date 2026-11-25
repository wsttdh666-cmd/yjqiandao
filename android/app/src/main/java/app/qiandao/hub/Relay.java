package app.qiandao.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class Relay {
    static final int PORT = 8317;
    static Relay running;

    interface Channel {
        boolean ready();
        String who();
        List<String> models();
        boolean owns(String model);
        void chat(JSONObject req, Sink sink) throws Exception;
        // 登录信息被换掉后（切号），按 prefs 里现在的那个号重新加载
        void reload();
    }

    static final class Upstream extends Exception {
        final int code;
        final long retryMs;

        Upstream(int code, String message, long retryMs) {
            super(message);
            this.code = code;
            this.retryMs = retryMs;
        }
    }

    final SharedPreferences prefs;
    final List<Channel> channels = new ArrayList<>();
    final Codex codex;
    final Playground pg;
    final Antigravity agy;
    final GeminiWeb web;
    private ServerSocket server;
    // 每条线各排各的队：pg 卡住不耽误网页；一条线 429 也只冷却它自己
    private final Map<Channel, Lane> lanes = new HashMap<>();

    private static final class Lane {
        final Semaphore slots;
        long lastStart, coolUntil;
        volatile boolean worked, denied;

        Lane(int n) {
            slots = new Semaphore(n, true);
        }
    }

    Relay(Context ctx) {
        prefs = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE);
        codex = new Codex(this);
        pg = new Playground(this, ctx);
        agy = new Antigravity(this);
        web = new GeminiWeb(this, ctx);
        channels.add(codex);
        channels.add(pg);
        channels.add(agy);
        channels.add(web);
    }

    interface Callback {
        String got(Uri url) throws Exception;
    }

    private static final Map<Integer, ServerSocket> waiting = new HashMap<>();

    static void listen(int port, String path, Callback callback, Consumer<String> done) throws IOException {
        ServerSocket server;
        synchronized (waiting) {
            ServerSocket old = waiting.remove(port);
            if (old != null) old.close();
            server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(1800000);
            waiting.put(port, server);
        }
        new Thread(() -> {
            String result;
            boolean got = false;
            try (ServerSocket ss = server) {
                while (true) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(3000);
                        String[] first;
                        try {
                            InputStream in = new BufferedInputStream(s.getInputStream());
                            first = line(in).split(" ");
                            while (!line(in).isEmpty());
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        if (first.length < 2 || !first[1].startsWith(path)) continue;
                        got = true;
                        String page = "<meta name=viewport content='width=device-width'><h3 style='font-family:sans-serif;text-align:center;margin-top:30vh'>回到「一键签到」看结果</h3>";
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" + page).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        result = callback.got(Uri.parse("http://localhost" + first[1]));
                        break;
                    }
                }
            } catch (Exception e) {
                synchronized (waiting) {
                    if (waiting.get(port) != server) return;
                }
                result = !got && e instanceof SocketTimeoutException ? "等太久没登完，重新点登录" : "登录没成功：" + e.getMessage();
            }
            synchronized (waiting) {
                waiting.remove(port);
            }
            done.accept(result);
        }).start();
    }

    static String text(Object content) {
        if (!(content instanceof JSONArray)) return content == null || content == JSONObject.NULL ? "" : content.toString();
        StringBuilder s = new StringBuilder();
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && "text".equals(p.optString("type"))) s.append(p.optString("text"));
        }
        return s.toString();
    }

    synchronized int spent(String who, boolean add) {
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
        JSONObject used = new JSONObject();
        try {
            used = new JSONObject(prefs.getString("used", "{}"));
        } catch (Exception ignored) {
        }
        if (!day.equals(used.optString("day"))) used = new JSONObject();
        int n = used.optInt(who) + (add ? 1 : 0);
        if (add) {
            try {
                prefs.edit().putString("used", used.put("day", day).put(who, n).toString()).apply();
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static String key(SharedPreferences prefs) {
        String k = prefs.getString("key", null);
        if (k == null) {
            k = "sk-local-" + random(16);
            prefs.edit().putString("key", k).apply();
        }
        return k;
    }

    JSONObject load(String name) {
        String sealed = prefs.getString(name, null);
        try {
            return sealed == null ? null : new JSONObject(Vault.unseal(sealed));
        } catch (Exception e) {
            return null;
        }
    }

    void save(String name, JSONObject value) throws Exception {
        if (value == null) prefs.edit().remove(name).apply();
        else prefs.edit().putString(name, Vault.seal(value.toString())).apply();
    }

    // 面板上这个号的状态：整个号被拒时盖掉原来的邮箱 / 已登录
    String show(Channel c, String normal) {
        Lane l = lanes.get(c);
        return normal != null && l != null && l.denied ? "这个号用不了，重新登录试试" : normal;
    }

    void fresh() {
        for (Lane l : lanes.values()) l.worked = l.denied = false;
    }

    // 多个号：当前号的登录信息就是 prefs 里这几项；存起来的其它号放在「<转接>Saved」里，切换时原样搬进搬出
    private static String[] keys(String p) {
        switch (p) {
            case "gpt": return new String[]{"codex"};
            case "agy": return new String[]{"antigravity"};
            case "pg": return new String[]{"pgCookies", "pgEmail", "pgModels", "pgProfile"};
            default: return new String[]{"webCookies", "webEmail", "webProfile"};
        }
    }

    private Channel channel(String p) {
        switch (p) {
            case "gpt": return codex;
            case "agy": return agy;
            case "pg": return pg;
            default: return web;
        }
    }

    private String email(String p) {
        switch (p) {
            case "gpt": return codex.email();
            case "agy": return agy.email();
            case "pg": return pg.email();
            default: return web.email();
        }
    }

    JSONArray saved(String p) {
        try {
            return new JSONArray(prefs.getString(p + "Saved", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // 把当前号收进已存列表，当前位置空出来
    private void park(String p) throws Exception {
        String email = email(p);
        if (email == null) return;
        JSONObject a = new JSONObject().put("email", email);
        SharedPreferences.Editor e = prefs.edit();
        for (String k : keys(p)) {
            if (prefs.contains(k)) a.put(k, prefs.getString(k, null));
            e.remove(k);
        }
        e.putString(p + "Saved", saved(p).put(a).toString()).commit();
    }

    private void switched(String p) {
        channel(p).reload();
        fresh();
    }

    synchronized void add(String p) throws Exception {
        boolean page = p.equals("pg") || p.equals("web");
        // 老 WebView 只有一个 Cookie 盒子，新号会把旧号的 Cookie 顶掉
        if (page && !WebChannel.split()) throw new IllegalStateException("系统的 WebView 太旧，存不了多个谷歌号，先去应用商店更新「Android System WebView」");
        park(p);
        if (page) prefs.edit().putString(p + "Profile", p + "-" + random(4)).commit();
        switched(p);
    }

    synchronized void use(String p, int i) throws Exception {
        JSONArray list = saved(p);
        JSONObject a = list.getJSONObject(i);
        list.remove(i);
        prefs.edit().putString(p + "Saved", list.toString()).commit();
        park(p);
        SharedPreferences.Editor e = prefs.edit();
        for (String k : keys(p)) {
            if (a.has(k)) e.putString(k, a.getString(k));
            else e.remove(k);
        }
        e.commit();
        switched(p);
    }

    // 新登的号已经在存着的列表里：列表里那份旧的去掉，返回这个邮箱好提示一下
    synchronized String dedupe(String p) {
        String email = email(p);
        if (email == null || !email.contains("@")) return null;
        JSONArray list = saved(p);
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (!email.equals(a.optString("email"))) continue;
            list.remove(i);
            prefs.edit().putString(p + "Saved", list.toString()).commit();
            String box = a.optString(p + "Profile", p);
            if (a.has(p + "Cookies") && !box.equals(prefs.getString(p + "Profile", p))) {
                try {
                    WebChannel.forget(new JSONArray(a.optString(p + "Cookies")), box);
                } catch (Exception ignored) {
                }
            }
            return email;
        }
        return null;
    }

    synchronized void drop(String p, int i) throws Exception {
        JSONArray list = saved(p);
        JSONObject a = list.getJSONObject(i);
        list.remove(i);
        prefs.edit().putString(p + "Saved", list.toString()).commit();
        if (a.has(p + "Cookies")) WebChannel.forget(new JSONArray(a.getString(p + "Cookies")), a.optString(p + "Profile", p));
    }

    void start() throws IOException {
        // 网页版是在同一个页面里打字发送，同时两个会互相打乱
        for (Channel c : channels) lanes.put(c, new Lane(c == web ? 1 : prefs.getInt("parallel", 2)));
        server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        running = this;
        new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                } catch (IOException e) {
                    return;
                }
            }
        }).start();
    }

    void stop() {
        running = null;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { pg.close(); web.close(); });
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) >= 0 && c != '\n'; ) if (c != '\r') b.write(c);
        return b.toString("UTF-8");
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    static Upstream upstream(String who, HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        String body = read(c.getErrorStream()), message = body;
        try {
            JSONObject j = new JSONObject(body);
            Object e = j.opt("error");
            message = e instanceof JSONObject ? ((JSONObject) e).optString("message", body) : j.optString("message", body);
        } catch (Exception ignored) {
        }
        long retry = 0;
        String after = c.getHeaderField("Retry-After");
        if (after != null && after.matches("\\d+")) retry = Long.parseLong(after) * 1000;
        if (message.length() > 300) message = message.substring(0, 300);
        return new Upstream(code, who + " 返回错误（" + code + "）：" + message, retry);
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String[] first = line(in).split(" ");
            if (first.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            for (String l; !(l = line(in)).isEmpty(); ) {
                int i = l.indexOf(':');
                if (i > 0) headers.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            byte[] body = new byte[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
            for (int n = 0, r; n < body.length && (r = in.read(body, n, body.length - n)) > 0; ) n += r;
            String method = first[0], path = first[1].split("\\?")[0];
            if (method.equals("OPTIONS")) {
                Sink.head(out, 204, "text/plain");
                return;
            }
            String key = key(prefs);
            String given = headers.getOrDefault("authorization", "").replaceFirst("(?i)^bearer\\s+", "");
            if (!key.equals(given) && !key.equals(headers.get("x-api-key"))) {
                Sink.error(out, 401, "密钥不对");
            } else if (method.equals("GET") && path.endsWith("/models")) {
                models(out);
            } else if (method.equals("POST") && path.endsWith("/chat/completions")) {
                // 请求发完对方就不会再发东西了，读到头说明酒馆那边取消 / 断开了：不再排队、不再等上游
                Thread worker = Thread.currentThread();
                new Thread(() -> {
                    try {
                        while (in.read() >= 0) ;
                    } catch (IOException ignored) {
                    }
                    worker.interrupt();
                }).start();
                chat(new JSONObject(new String(body, StandardCharsets.UTF_8)), out);
            } else {
                Sink.error(out, 404, "没有这个接口");
            }
        } catch (Exception ignored) {
        }
    }

    private void models(OutputStream out) throws Exception {
        JSONArray data = new JSONArray();
        Set<String> bad = prefs.getStringSet("bad", new HashSet<>());
        for (Channel c : channels) {
            if (!c.ready()) continue;
            for (String m : c.models()) if (!bad.contains(m)) data.put(new JSONObject().put("id", m).put("object", "model").put("owned_by", "relay"));
        }
        Sink.json(out, 200, new JSONObject().put("object", "list").put("data", data));
    }

    final ArrayDeque<JSONObject> log = new ArrayDeque<>();

    private void note(String model, boolean ok, String detail) throws Exception {
        synchronized (log) {
            log.addFirst(new JSONObject().put("t", System.currentTimeMillis() / 1000).put("model", model).put("ok", ok).put("detail", detail));
            if (log.size() > 100) log.removeLast();
        }
    }

    private void chat(JSONObject req, OutputStream out) throws Exception {
        String model = req.optString("model");
        Channel channel = null;
        for (Channel c : channels) if (c.ready() && c.owns(model)) channel = c;
        if (channel == null) {
            String m = "没有这个模型，或者对应的账号还没登录：" + model;
            note(model, false, m);
            Sink.error(out, 404, m);
            return;
        }
        int cap = prefs.getInt("cap", 50);
        if (cap > 0 && spent(channel.who(), false) >= cap) {
            String m = "这个号今天已经用了 " + cap + " 次，到每日上限了，明天再用（上限可以在订阅转接里调）";
            note(model, false, m);
            Sink.error(out, 429, m);
            return;
        }
        Lane lane = lanes.get(channel);
        long wait = lane.coolUntil - System.currentTimeMillis();
        if (wait > 0) {
            String m = "额度用完了，" + (wait / 60000 + 1) + " 分钟后再试";
            note(model, false, m);
            Sink.error(out, 429, m);
            return;
        }
        lane.slots.acquire();
        try {
            synchronized (lane) {
                long gap = lane.lastStart + prefs.getInt("gap", 3) * 1000L - System.currentTimeMillis();
                if (gap > 0) Thread.sleep(gap);
                lane.lastStart = System.currentTimeMillis();
            }
            spent(channel.who(), true);
            Sink sink = new Sink(out, req.optBoolean("stream"), model);
            long begin = System.currentTimeMillis();
            try {
                channel.chat(req, sink);
                sink.finish();
                note(model, true, "回复 " + sink.chars + " 字 · " + (System.currentTimeMillis() - begin) / 1000 + " 秒");
                lane.worked = true;
                lane.denied = false;
            } catch (Upstream e) {
                note(model, false, e.getMessage());
                if (e.code == 429) lane.coolUntil = System.currentTimeMillis() + Math.max(e.retryMs, 60000);
                // 这个号还没成功过就 403：多半是整个号的问题，标在号上，不藏模型
                if (e.code == 403 && !lane.worked) lane.denied = true;
                // 这个号没权限用 / 没有这个模型：记下来，下次拉取就不列了（转接面板里能重新检查）
                else if (e.code == 403 || e.code == 404) synchronized (prefs) {
                    Set<String> bad = new HashSet<>(prefs.getStringSet("bad", new HashSet<>()));
                    bad.add(model);
                    prefs.edit().putStringSet("bad", bad).apply();
                }
                sink.fail(e.code, e.getMessage());
            } catch (Exception e) {
                note(model, false, "连不上：" + e.getMessage());
                sink.fail(502, "连不上：" + e.getMessage());
            }
        } finally {
            lane.slots.release();
        }
    }

    static final class Sink {
        final OutputStream out;
        final boolean stream;
        final String model, id = "chatcmpl-" + System.nanoTime();
        final StringBuilder text = new StringBuilder(), think = new StringBuilder();
        String finish = "stop";
        int chars;
        private boolean started;

        Sink(OutputStream out, boolean stream, String model) {
            this.out = out;
            this.stream = stream;
            this.model = model;
        }

        static void head(OutputStream out, int code, String type) throws IOException {
            out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "; charset=utf-8\r\nCache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        static void json(OutputStream out, int code, JSONObject body) throws IOException {
            head(out, code, "application/json");
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        static void error(OutputStream out, int code, String message) throws Exception {
            json(out, code, new JSONObject().put("error", new JSONObject().put("message", message).put("code", code)));
        }

        private void event(JSONObject data) throws IOException {
            out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private JSONObject chunk(JSONObject delta, String reason) throws Exception {
            JSONObject choice = new JSONObject().put("index", 0).put("delta", delta).put("finish_reason", reason == null ? JSONObject.NULL : reason);
            return new JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", System.currentTimeMillis() / 1000)
                .put("model", model).put("choices", new JSONArray().put(choice));
        }

        void delta(String content, String reasoning) throws Exception {
            if (content != null) chars += content.length();
            if (!stream) {
                if (content != null) text.append(content);
                if (reasoning != null) think.append(reasoning);
                return;
            }
            if (!started) {
                head(out, 200, "text/event-stream");
                started = true;
            }
            JSONObject d = new JSONObject();
            if (content != null) d.put("content", content);
            if (reasoning != null) d.put("reasoning_content", reasoning);
            event(chunk(d, null));
        }

        void finish() throws Exception {
            if (stream) {
                if (!started) head(out, 200, "text/event-stream");
                started = true;
                event(chunk(new JSONObject(), finish));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            JSONObject message = new JSONObject().put("role", "assistant").put("content", text.toString());
            if (think.length() > 0) message.put("reasoning_content", think.toString());
            JSONObject choice = new JSONObject().put("index", 0).put("message", message).put("finish_reason", finish);
            json(out, 200, new JSONObject().put("id", id).put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000)
                .put("model", model).put("choices", new JSONArray().put(choice)));
        }

        void fail(int code, String message) throws Exception {
            if (!started) {
                error(out, code, message);
                return;
            }
            event(new JSONObject().put("error", new JSONObject().put("message", message).put("code", code)));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
