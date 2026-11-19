package app.qiandao.hub;

import android.content.Intent;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

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

    private static SecretKey dataKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias("data")) return (SecretKey) store.getKey("data", null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder("data", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return gen.generateKey();
    }

    @PluginMethod
    public void seal(PluginCall call) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKey());
            byte[] iv = cipher.getIV(), body = cipher.doFinal(call.getString("text").getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            JSObject result = new JSObject();
            result.put("text", Base64.encodeToString(all, Base64.NO_WRAP));
            call.resolve(result);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void unseal(PluginCall call) {
        try {
            byte[] all = Base64.decode(call.getString("text"), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dataKey(), new GCMParameterSpec(128, all, 0, 12));
            JSObject result = new JSObject();
            result.put("text", new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8));
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
