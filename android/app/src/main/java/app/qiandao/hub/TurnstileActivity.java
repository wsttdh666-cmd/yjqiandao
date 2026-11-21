package app.qiandao.hub;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.Collections;

// 在站点自己的域名上渲染真·Cloudflare 验证框，拿到 token 返回（常常自动过，偶尔要用户点一下）
public class TurnstileActivity extends Activity {
    static PluginCall pending;
    private WebView web;
    private boolean started, done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        TextView tip = new TextView(this);
        tip.setText("正在验证…需要的话点一下方框");
        tip.setPadding(24, 16, 24, 16);
        web = new WebView(this);
        root.addView(tip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", "").replaceFirst("Version/\\S+ ", ""));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        String url = getIntent().getStringExtra("url");
        String sitekey = getIntent().getStringExtra("sitekey");
        String inject = "(function(){if(window.__cfInit)return;window.__cfInit=1;window.__cf='';"
            + "var s=document.createElement('script');s.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';"
            + "s.onload=function(){var b=document.createElement('div');b.style.cssText='position:fixed;top:12px;left:12px;z-index:2147483647;background:#fff;padding:12px;border-radius:8px';document.body.appendChild(b);"
            + "try{turnstile.render(b,{sitekey:" + org.json.JSONObject.quote(sitekey) + ",callback:function(t){window.__cf=t;}});}catch(e){window.__cf='ERR'+e;}};"
            + "document.head.appendChild(s);})();";

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String current) {
                if (started || !host(current).equals(host(url))) return;
                started = true;
                view.evaluateJavascript(inject, null);
                poll();
            }
        });
        web.loadUrl(url);
        web.postDelayed(() -> finishWith(""), 90000);
    }

    private void poll() {
        if (done) return;
        web.evaluateJavascript("window.__cf||''", v -> {
            if (done) return;
            String t = v == null ? "" : v.replaceAll("^\"|\"$", "");
            if (t.isEmpty()) web.postDelayed(this::poll, 500);
            else finishWith(t.startsWith("ERR") ? "" : t);
        });
    }

    private static String host(String url) {
        String h = url == null ? "" : Uri.parse(url).getHost();
        if (h == null) h = "";
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private void finishWith(String token) {
        if (done) return;
        done = true;
        if (pending != null) {
            pending.resolve(new JSObject().put("token", token));
            pending = null;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWith("");
    }
}
