package app.qiandao.hub;

import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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
