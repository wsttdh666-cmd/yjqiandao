package app.qiandao.hub;

import android.app.Activity;
import android.graphics.Bitmap;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.getcapacitor.JSObject;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.PluginCall;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoginActivity extends Activity {
    static PluginCall pending;
    private WebView web;
    private TextView status;
    private Runnable slow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        Button done = new Button(this);
        done.setText("登录好了，完成");
        status = new TextView(this);
        status.setPadding(24, 4, 24, 8);
        status.setTextSize(12);
        web = new WebView(this);
        root.addView(done, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        String ua = settings.getUserAgentString().replace("; wv", "").replaceFirst("Version/\\S+ ", "");
        Matcher chrome = Pattern.compile("Chrome/(\\S+)").matcher(ua);
        settings.setUserAgentString(chrome.find() ? ua + " EdgA/" + chrome.group(1) : ua);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    loading(url.getHost());
                    return false;
                }
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

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                web.removeCallbacks(slow);
                status.setText("正在加载 " + Uri.parse(url).getHost() + "，网速慢要多等一会…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                web.removeCallbacks(slow);
                status.setText(Uri.parse(url).getHost());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                web.removeCallbacks(slow);
                status.setText("打不开 " + request.getUrl().getHost() + "：" + error.getDescription());
            }
        });
        String start = getIntent().getStringExtra("url");
        loading(Uri.parse(start).getHost());
        web.loadUrl(start);
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

    private void loading(String host) {
        status.setText("正在打开 " + host + "…");
        web.removeCallbacks(slow);
        slow = () -> status.setText("15 秒还没连上 " + host + "，多半是网络或 VPN 的问题");
        web.postDelayed(slow, 15000);
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
