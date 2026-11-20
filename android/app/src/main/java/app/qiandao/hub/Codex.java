package app.qiandao.hub;

import android.net.Uri;
import android.util.Base64;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class Codex implements Relay.Channel {
    private static final String AUTHORIZE = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String API = "https://api.openai.com/v1";
    private static final String REDIRECT = "http://127.0.0.1:1455/auth/callback";
    private static final String AGENT = "QiandaoHub";
    private static final List<String> MODELS = Arrays.asList(
        "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra", "gpt-6.1-sol", "gpt-6-astra",
        "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna");

    private final Relay relay;
    private JSONObject account;

    Codex(Relay relay) {
        this.relay = relay;
        account = relay.load("codex");
    }

    String email() {
        return account == null ? null : account.optString("email", "已登录");
    }

    void logout() throws Exception {
        account = null;
        relay.save("codex", null);
    }

    public boolean ready() {
        return account != null;
    }

    public List<String> models() {
        return MODELS;
    }

    public boolean owns(String model) {
        return model.startsWith("gpt-");
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    String begin(Consumer<String> done) throws Exception {
        String verifier = Relay.random(32), state = Relay.random(16), nonce = Relay.random(16);
        String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String device = relay.prefs.getString("device", null);
        if (device == null) {
            device = UUID.randomUUID().toString();
            relay.prefs.edit().putString("device", device).apply();
        }
        String deviceId = device;
        ServerSocket callback = new ServerSocket(1455, 4, InetAddress.getByName("127.0.0.1"));
        callback.setSoTimeout(300000);
        new Thread(() -> {
            String result;
            try (ServerSocket server = callback) {
                while (true) {
                    try (Socket s = server.accept()) {
                        String[] first = Relay.line(new BufferedInputStream(s.getInputStream())).split(" ");
                        if (first.length < 2 || !first[1].startsWith("/auth/callback")) continue;
                        Uri u = Uri.parse("http://localhost" + first[1]);
                        String code = u.getQueryParameter("code");
                        if (!state.equals(u.getQueryParameter("state")) || code == null) {
                            result = "登录没成功：" + u.getQueryParameter("error");
                        } else {
                            exchange(code, u.getQueryParameter("client_id"), verifier, deviceId);
                            result = null;
                        }
                        String page = "<meta name=viewport content='width=device-width'><h3 style='font-family:sans-serif;text-align:center;margin-top:30vh'>"
                            + (result == null ? "登录成功，回到「一键签到」就好" : result) + "</h3>";
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" + page).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        break;
                    }
                }
            } catch (Exception e) {
                result = "登录没成功：" + e.getMessage();
            }
            done.accept(result);
        }).start();
        return AUTHORIZE + "?client_id=dynamic_agent_client&agent_name_hint=" + AGENT
            + "&ext_agent_host_id=" + Uri.encode("urn:uuid:" + device) + "&response_type=code&redirect_uri=" + Uri.encode(REDIRECT)
            + "&resource=" + Uri.encode(API) + "&scope=" + Uri.encode("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
            + "&state=" + state + "&code_challenge=" + challenge + "&code_challenge_method=S256&nonce=" + nonce;
    }

    private JSONObject token(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        c.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
        if (c.getResponseCode() != 200) throw Relay.upstream("OpenAI 登录", c);
        return new JSONObject(Relay.read(c.getInputStream()));
    }

    private void keep(JSONObject t, String client) throws Exception {
        JSONObject a = account == null ? new JSONObject() : account;
        a.put("client", client).put("access", t.getString("access_token"))
            .put("refresh", t.optString("refresh_token", a.optString("refresh")))
            .put("expires", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000);
        String id = t.optString("id_token");
        if (!id.isEmpty()) {
            JSONObject claims = new JSONObject(new String(Base64.decode(id.split("\\.")[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
            a.put("email", claims.optString("email", "已登录"));
        }
        account = a;
        relay.save("codex", a);
    }

    private void exchange(String code, String client, String verifier, String device) throws Exception {
        keep(token("grant_type=authorization_code&client_id=" + Uri.encode(client) + "&code=" + Uri.encode(code)
            + "&code_verifier=" + verifier + "&redirect_uri=" + Uri.encode(REDIRECT) + "&resource=" + Uri.encode(API)), client);
    }

    private synchronized String access() throws Exception {
        if (System.currentTimeMillis() > account.getLong("expires") - 300000) {
            String client = account.getString("client");
            keep(token("grant_type=refresh_token&client_id=" + Uri.encode(client) + "&refresh_token=" + Uri.encode(account.getString("refresh"))
                + "&resource=" + Uri.encode(API)), client);
        }
        return account.getString("access");
    }

    private static String text(Object content) {
        if (!(content instanceof JSONArray)) return content == null || content == JSONObject.NULL ? "" : content.toString();
        StringBuilder s = new StringBuilder();
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && "text".equals(p.optString("type"))) s.append(p.optString("text"));
        }
        return s.toString();
    }

    public void chat(JSONObject req, Relay.Sink sink) throws Exception {
        StringBuilder instructions = new StringBuilder();
        JSONArray input = new JSONArray(), messages = req.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.optString("role");
            Object content = m.opt("content");
            if (role.equals("system") || role.equals("developer")) {
                instructions.append(text(content)).append("\n\n");
                continue;
            }
            boolean mine = role.equals("assistant");
            JSONArray parts = new JSONArray();
            if (content instanceof JSONArray) {
                JSONArray list = (JSONArray) content;
                for (int k = 0; k < list.length(); k++) {
                    JSONObject p = list.optJSONObject(k);
                    if (p == null) continue;
                    if ("image_url".equals(p.optString("type")) && !mine) {
                        JSONObject img = p.optJSONObject("image_url");
                        parts.put(new JSONObject().put("type", "input_image").put("image_url", img == null ? p.optString("image_url") : img.optString("url")));
                    } else if ("text".equals(p.optString("type"))) {
                        parts.put(new JSONObject().put("type", mine ? "output_text" : "input_text").put("text", p.optString("text")));
                    }
                }
            } else {
                parts.put(new JSONObject().put("type", mine ? "output_text" : "input_text").put("text", text(content)));
            }
            input.put(new JSONObject().put("role", mine ? "assistant" : "user").put("content", parts));
        }
        JSONObject body = new JSONObject().put("model", req.getString("model")).put("stream", true).put("store", false).put("input", input);
        if (instructions.length() > 0) body.put("instructions", instructions.toString().trim());
        String effort = req.optString("reasoning_effort");
        if (!effort.isEmpty()) body.put("reasoning", new JSONObject().put("effort", effort).put("summary", "auto"));

        HttpURLConnection c = (HttpURLConnection) new URL(API + "/responses").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setReadTimeout(300000);
        c.setRequestProperty("Authorization", "Bearer " + access());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("User-Agent", AGENT + " (android)");
        c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        if (c.getResponseCode() != 200) throw Relay.upstream("GPT", c);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (!l.startsWith("data:")) continue;
                String data = l.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                JSONObject ev = new JSONObject(data);
                switch (ev.optString("type")) {
                    case "response.output_text.delta":
                        sink.delta(ev.optString("delta"), null);
                        break;
                    case "response.reasoning_summary_text.delta":
                        sink.delta(null, ev.optString("delta"));
                        break;
                    case "response.incomplete":
                        sink.finish = "length";
                        return;
                    case "response.completed":
                        return;
                    case "response.failed":
                    case "error":
                        JSONObject err = ev.optJSONObject("response") != null ? ev.getJSONObject("response").optJSONObject("error") : ev.optJSONObject("error");
                        throw new Relay.Upstream(502, "GPT 出错：" + (err == null ? data : err.optString("message", data)), 0);
                }
            }
        }
    }
}
