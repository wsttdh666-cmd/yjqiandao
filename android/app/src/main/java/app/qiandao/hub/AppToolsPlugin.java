package app.qiandao.hub;

import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import java.util.function.Consumer;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@CapacitorPlugin(name = "AppTools")
public class AppToolsPlugin extends Plugin {
    @PluginMethod
    public void install(PluginCall call) {
        String url = call.getString("url");
        new Thread(() -> {
            try {
                File apk = new File(getContext().getCacheDir(), "update.apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    long total = conn.getContentLengthLong(), done = 0;
                    int last = -1;
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int step = (int) (total > 0 ? done * 100 / total : done >> 20);
                        if (step != last) {
                            last = step;
                            JSObject p = new JSObject();
                            p.put("done", done);
                            p.put("total", total);
                            notifyListeners("progress", p);
                        }
                    }
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    private JSObject state() {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("relay", android.content.Context.MODE_PRIVATE);
        Relay r = Relay.running;
        JSObject s = new JSObject();
        s.put("running", r != null);
        s.put("url", "http://127.0.0.1:" + Relay.PORT + "/v1");
        s.put("key", Relay.key(prefs));
        s.put("parallel", prefs.getInt("parallel", 2));
        s.put("gap", prefs.getInt("gap", 3));
        s.put("cap", prefs.getInt("cap", 50));
        s.put("hidden", prefs.getStringSet("bad", new java.util.HashSet<>()).size());
        if (r != null) {
            s.put("gpt", r.codex.email());
            s.put("pg", r.pg.status());
            s.put("agy", r.agy.email());
            s.put("web", r.web.status());
        }
        return s;
    }

    @PluginMethod
    public void relay(PluginCall call) {
        Intent service = new Intent(getContext(), RelayService.class);
        if (call.getBoolean("on", false)) {
            androidx.core.content.ContextCompat.startForegroundService(getContext(), service);
            // 不在电池优化白名单里，切到别的 App 时系统会把转接冻住，请求一直没人接
            android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
                try {
                    getActivity().startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName())));
                } catch (Exception e) {
                    // 有的系统没有这个弹窗，就不管了，用户自己去电池设置里改
                }
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> call.resolve(state()), 600);
        } else {
            getContext().stopService(service);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> call.resolve(state()), 300);
        }
    }

    @PluginMethod
    public void relayState(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void relayRecheck(PluginCall call) {
        getContext().getSharedPreferences("relay", android.content.Context.MODE_PRIVATE).edit().remove("bad").apply();
        call.resolve(state());
    }

    @PluginMethod
    public void relaySet(PluginCall call) {
        getContext().getSharedPreferences("relay", android.content.Context.MODE_PRIVATE).edit()
            .putInt("parallel", call.getInt("parallel", 2))
            .putInt("gap", call.getInt("gap", 3))
            .putInt("cap", call.getInt("cap", 50))
            .apply();
        if (Relay.running == null) {
            call.resolve(state());
            return;
        }
        Intent service = new Intent(getContext(), RelayService.class);
        getContext().stopService(service);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            androidx.core.content.ContextCompat.startForegroundService(getContext(), service);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> call.resolve(state()), 600);
        }, 300);
    }

    @PluginMethod
    public void relayLogin(PluginCall call) {
        Relay r = Relay.running;
        if (r == null) {
            call.reject("先开启订阅转接");
            return;
        }
        String prov = call.getString("provider");
        if ("pg".equals(prov) || "web".equals(prov)) {
            Consumer<String> back = error -> {
                if (error == null) call.resolve(state());
                else call.reject(error);
            };
            if ("web".equals(prov)) r.web.login(call.getString("cookie", ""), back);
            else r.pg.login(call.getString("cookie", ""), back);
            return;
        }
        Consumer<String> done = error -> {
            if (error == null) call.resolve(state());
            else call.reject(error);
        };
        try {
            String url = "agy".equals(call.getString("provider")) ? r.agy.begin(done) : r.codex.begin(done);
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            call.reject("登录没成功：" + e.getMessage());
        }
    }

    @PluginMethod
    public void relayLogout(PluginCall call) {
        try {
            Relay r = Relay.running;
            String provider = call.getString("provider");
            if (r != null && "pg".equals(provider)) r.pg.logout();
            else if (r != null && "web".equals(provider)) r.web.logout();
            else if (r != null && "agy".equals(provider)) r.agy.logout();
            else if (r != null) r.codex.logout();
            call.resolve(state());
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void seal(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("text", Vault.seal(call.getString("text")));
            call.resolve(result);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void unseal(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("text", Vault.unseal(call.getString("text")));
            call.resolve(result);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void share(PluginCall call) {
        try {
            File file = new File(getContext().getCacheDir(), call.getString("name"));
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(call.getString("text").getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(Intent.createChooser(send, "导出到"));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url")));
        getActivity().startActivity(Intent.createChooser(view, "选择浏览器"));
        call.resolve();
    }

    @PluginMethod
    public void webLogin(PluginCall call) {
        LoginActivity.pending = call;
        getActivity().startActivity(new Intent(getContext(), LoginActivity.class).putExtra("url", call.getString("url")).putExtra("scan", call.getString("scan")).putExtra("optional", call.getBoolean("optional", false)));
    }
}
