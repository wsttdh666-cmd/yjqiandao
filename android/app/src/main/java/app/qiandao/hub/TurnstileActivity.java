package app.qiandao.hub;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

// 把所有要人机验证的站，叠在一个能上下滚的弹窗里，各自在自己的域名上渲染真·Cloudflare 验证框，拿到各自 token 一起返回
public class TurnstileActivity extends Activity {
    static PluginCall pending;
    private final JSObject tokens = new JSObject();
    private int total, got;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        TextView tip = new TextView(this);
        tip.setText("人机验证：需要的话点一下方框，过了会自动收起");
        tip.setPadding(28, 20, 28, 12);
        root.addView(tip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button finish = new Button(this);
        finish.setText("完成");
        finish.setOnClickListener(v -> finishAll());
        root.addView(finish, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        JSONArray sites = parseSites();
        total = sites.length();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject o = sites.optJSONObject(i);
            if (o != null) addSite(list, o.optString("url"), o.optString("sitekey"), o.optString("name"));
        }
        if (total == 0) finishAll();
        root.postDelayed(this::finishAll, 180000);
    }

    private JSONArray parseSites() {
        String raw = getIntent().getStringExtra("sites");
        if (raw != null && !raw.isEmpty()) {
            try { return new JSONArray(raw); } catch (Exception ignore) {}
        }
        JSONArray a = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("url", getIntent().getStringExtra("url"));
            o.put("sitekey", getIntent().getStringExtra("sitekey"));
            o.put("name", getIntent().getStringExtra("name"));
            a.put(o);
        } catch (Exception ignore) {}
        return a;
    }

    private void addSite(LinearLayout list, final String url, String sitekey, String name) {
        TextView label = new TextView(this);
        label.setText(name == null || name.isEmpty() ? url : name);
        label.setTextSize(15);
        label.setPadding(28, 16, 28, 6);
        list.addView(label);

        final WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", "").replaceFirst("Version/\\S+ ", ""));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        final String inject = "(function(){if(window.__cfInit)return;window.__cfInit=1;window.__cf='';"
            + "var s=document.createElement('script');s.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';"
            + "s.onload=function(){var b=document.createElement('div');b.style.cssText='position:fixed;top:12px;left:12px;z-index:2147483647;background:#fff;padding:12px;border-radius:8px';document.body.appendChild(b);"
            + "try{turnstile.render(b,{sitekey:" + JSONObject.quote(sitekey) + ",callback:function(t){window.__cf=t;}});}catch(e){window.__cf='ERR'+e;}};"
            + "document.head.appendChild(s);})();";

        web.setWebViewClient(new WebViewClient() {
            boolean started;
            @Override
            public void onPageFinished(WebView view, String current) {
                if (started || !host(current).equals(host(url))) return;
                started = true;
                view.evaluateJavascript(inject, null);
                pollOne(web, url);
            }
        });
        web.loadUrl(url);
        list.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));
    }

    private void pollOne(final WebView web, final String url) {
        if (done || tokens.has(url)) return;
        web.evaluateJavascript("window.__cf||''", v -> {
            if (done || tokens.has(url)) return;
            String t = v == null ? "" : v.replaceAll("^\"|\"$", "");
            if (t.startsWith("ERR")) return;
            if (t.isEmpty()) {
                web.postDelayed(() -> pollOne(web, url), 500);
            } else {
                tokens.put(url, t);
                got++;
                if (got >= total) finishAll();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String host(String url) {
        String h = url == null ? "" : Uri.parse(url).getHost();
        if (h == null) h = "";
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private void finishAll() {
        if (done) return;
        done = true;
        if (pending != null) {
            pending.resolve(tokens);
            pending = null;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        finishAll();
    }
}
