package app.qiandao.hub;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import java.util.function.Consumer;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@CapacitorPlugin(name = "AppTools")
public class AppToolsPlugin extends Plugin {
    // 交给系统下载器：切到别的 App 也接着下，断网能续，通知栏有进度；同一版本已在下或已下完就直接接上
    @PluginMethod
    public void install(PluginCall call) {
        DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        SharedPreferences prefs = getContext().getSharedPreferences("update", Context.MODE_PRIVATE);
        File apk = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
        String ver = call.getString("ver", "");
        long id = prefs.getLong("id", -1);
        long[] q = id >= 0 && ver.equals(prefs.getString("ver", "")) ? query(dm, id) : null;
        if (q == null || q[0] == DownloadManager.STATUS_FAILED || (q[0] == DownloadManager.STATUS_SUCCESSFUL && !apk.exists())) {
            if (id >= 0) dm.remove(id);
            apk.delete();
            id = dm.enqueue(new DownloadManager.Request(Uri.parse(call.getString("url")))
                .setTitle("一键签到 新版本")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(getContext(), Environment.DIRECTORY_DOWNLOADS, "update.apk"));
            prefs.edit().putLong("id", id).putString("ver", ver).apply();
        }
        final long task = id;
        Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                long[] s = query(dm, task);
                if (s == null || s[0] == DownloadManager.STATUS_FAILED) {
                    call.reject("下载失败");
                    return;
                }
                notifyListeners("progress", new JSObject().put("done", s[1]).put("total", s[2]));
                // 下完了但人在别的 App 里，等回到我们这边再弹安装
                if (s[0] != DownloadManager.STATUS_SUCCESSFUL || !getActivity().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    h.postDelayed(this, 500);
                    return;
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                getContext().startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                call.resolve();
            }
        });
    }

    private static long[] query(DownloadManager dm, long id) {
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) return null;
            return new long[] {
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            };
        }
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
            s.put("gpt", r.show(r.codex, r.codex.email()));
            s.put("pg", r.show(r.pg, r.pg.status()));
            s.put("agy", r.show(r.agy, r.agy.email()));
            s.put("web", r.show(r.web, r.web.status()));
            JSObject saved = new JSObject();
            for (String p : new String[]{"gpt", "agy", "pg", "web"}) {
                com.getcapacitor.JSArray emails = new com.getcapacitor.JSArray();
                org.json.JSONArray list = r.saved(p);
                for (int i = 0; i < list.length(); i++) emails.put(list.optJSONObject(i).optString("email"));
                saved.put(p, emails);
            }
            s.put("saved", saved);
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
    public void relayLogs(PluginCall call) {
        com.getcapacitor.JSArray list = new com.getcapacitor.JSArray();
        Relay r = Relay.running;
        if (r != null) synchronized (r.log) {
            for (org.json.JSONObject l : r.log) list.put(l);
        }
        call.resolve(new JSObject().put("list", list));
    }

    @PluginMethod
    public void relayRecheck(PluginCall call) {
        getContext().getSharedPreferences("relay", android.content.Context.MODE_PRIVATE).edit().remove("bad").apply();
        if (Relay.running != null) Relay.running.fresh();
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
        Consumer<String> done = error -> {
            if (error != null) {
                call.reject(error);
                return;
            }
            // 问谷歌要账号列表要联网，不能放在主线程
            new Thread(() -> {
                String dup = r.dedupe(prov);
                int more = 0;
                String fail = null;
                try {
                    more = r.expand(prov);
                } catch (Exception e) {
                    fail = String.valueOf(e);
                }
                JSObject s = state();
                if (fail != null) s.put("moreFail", fail);
                if (dup != null) s.put("dup", dup);
                if (more > 0) s.put("more", more);
                call.resolve(s);
            }).start();
        };
        if ("pg".equals(prov) || "web".equals(prov)) {
            if ("web".equals(prov)) r.web.login(call.getString("cookie", ""), done);
            else r.pg.login(call.getString("cookie", ""), done);
            return;
        }
        try {
            String url = "agy".equals(call.getString("provider")) ? r.agy.begin(done) : r.codex.begin(done);
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            call.reject("登录没成功：" + e.getMessage());
        }
    }

    // 多个号：add 把当前号存起来、腾出位置给新号登录；use 切到存着的第 index 个；drop 删掉它
    @PluginMethod
    public void relayAccount(PluginCall call) {
        Relay r = Relay.running;
        if (r == null) {
            call.reject("先开启订阅转接");
            return;
        }
        String p = call.getString("provider");
        int i = call.getInt("index", 0);
        try {
            switch (call.getString("action", "")) {
                case "add": r.add(p); break;
                case "use": r.use(p, i); break;
                default: r.drop(p, i);
            }
            call.resolve(state());
        } catch (Exception e) {
            call.reject(e.getMessage());
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
            if (r != null) r.fresh();
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

    @PluginMethod
    public void turnstile(PluginCall call) {
        TurnstileActivity.pending = call;
        Intent intent = new Intent(getContext(), TurnstileActivity.class);
        com.getcapacitor.JSArray sites = call.getArray("sites", null);
        if (sites != null) {
            intent.putExtra("sites", sites.toString());
        } else {
            intent.putExtra("url", call.getString("url")).putExtra("sitekey", call.getString("sitekey")).putExtra("name", call.getString("name"));
        }
        getActivity().startActivity(intent);
    }
}
