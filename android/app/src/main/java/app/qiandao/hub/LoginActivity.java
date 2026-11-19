package app.qiandao.hub;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

public class LoginActivity extends Activity {
    static PluginCall pending;
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        Button done = new Button(this);
        done.setText("登录好了，完成");
        web = new WebView(this);
        root.addView(done, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }
        });
        web.loadUrl(getIntent().getStringExtra("url"));
        done.setOnClickListener(v -> {
            if (!onSite()) {
                Toast.makeText(this, "还没回到站点页面，登录完再点", Toast.LENGTH_SHORT).show();
                return;
            }
            web.evaluateJavascript("JSON.stringify(Object.assign({}, localStorage))", this::finishWith);
        });
    }

    private static String host(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        if (h == null) return "";
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private boolean onSite() {
        String site = host(getIntent().getStringExtra("url")), now = host(web.getUrl());
        return now.equals(site) || now.endsWith("." + site) || site.endsWith("." + now);
    }

    private void finishWith(String storage) {
        CookieManager cookies = CookieManager.getInstance();
        String url = web.getUrl();
        String all = url == null ? null : cookies.getCookie(url);
        if (all != null) {
            for (String pair : all.split(";\\s*")) cookies.setCookie(url, pair + "; Max-Age=2592000; Path=/");
        }
        cookies.flush();
        if (pending != null) {
            JSObject result = new JSObject();
            result.put("storage", storage);
            pending.resolve(result);
            pending = null;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else finishWith("null");
    }
}
