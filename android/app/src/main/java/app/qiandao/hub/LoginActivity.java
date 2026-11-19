package app.qiandao.hub;

import android.app.Activity;
import android.content.Intent;
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
                Uri url = request.getUrl();
                String scheme = url.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                if ("intent".equals(scheme)) {
                    try {
                        String fallback = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url");
                        if (fallback != null) {
                            view.loadUrl(fallback);
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
                Toast.makeText(LoginActivity.this, "网页想跳到别的 App（" + scheme + "），已拦下", Toast.LENGTH_LONG).show();
                return true;
            }
        });
        web.loadUrl(getIntent().getStringExtra("url"));
        done.setOnClickListener(v -> {
            if (!onSite()) {
                Toast.makeText(this, "还没回到站点页面，登录完再点", Toast.LENGTH_SHORT).show();
                return;
            }
            String scan = getIntent().getStringExtra("scan");
            boolean optional = getIntent().getBooleanExtra("optional", false);
            web.evaluateJavascript("JSON.stringify(Object.assign({}, localStorage))", storage -> {
                if (scan == null) {
                    finishWith(storage, null);
                    return;
                }
                web.evaluateJavascript(scan, key -> {
                    boolean none = key == null || key.equals("null") || key.equals("\"\"");
                    if (none && !optional) {
                        Toast.makeText(this, "这页没找到密钥，翻到显示密钥的页面再点完成", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    finishWith(storage, none ? null : key);
                });
            });
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

    private void finishWith(String storage, String key) {
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
            result.put("key", key);
            pending.resolve(result);
            pending = null;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else finishWith("null", null);
    }
}
