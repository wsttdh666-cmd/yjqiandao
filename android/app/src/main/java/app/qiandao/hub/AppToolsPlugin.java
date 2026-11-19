package app.qiandao.hub;

import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

@CapacitorPlugin(name = "AppTools")
public class AppToolsPlugin extends Plugin {
    @PluginMethod
    public void install(PluginCall call) {
        String url = call.getString("url");
        new Thread(() -> {
            try {
                File apk = new File(getContext().getCacheDir(), "update.apk");
                try (InputStream in = new URL(url).openStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
        getActivity().startActivity(new Intent(getContext(), LoginActivity.class).putExtra("url", call.getString("url")).putExtra("scan", call.getString("scan")));
    }
}
