package app.qiandao.hub;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.json.JSONArray;
import org.json.JSONObject;

// 底部弹窗：每个要人机验证的站一行，在站点自己域名上渲染真·Cloudflare 框（站点页面藏起来，只露框），全过自动收起
public class TurnstileActivity extends Activity {
    static PluginCall pending;
    private final JSObject tokens = new JSObject();
    private int total, got;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setFitsSystemWindows(true);
        root.setOnClickListener(v -> finishAll());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[] { dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0 });
        card.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("人机验证");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF222222);
        card.addView(title);

        final int maxH = getResources().getDisplayMetrics().heightPixels * 6 / 10;
        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        card.addView(scroll);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(16);
        cancel.setTextColor(0xFF444444);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFFF0F1F4);
        cbg.setCornerRadius(dp(14));
        cancel.setBackground(cbg);
        cancel.setOnClickListener(v -> finishAll());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        clp.topMargin = dp(14);
        card.addView(cancel, clp);

        root.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
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
        final String shown = name == null || name.isEmpty() ? url : name;
        final TextView label = new TextView(this);
        label.setText(shown);
        label.setTextSize(14);
        label.setTextColor(0xFF666666);
        label.setPadding(0, dp(14), 0, dp(6));
        list.addView(label);

        final WebView web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.setVisibility(View.INVISIBLE);
        // Cloudflare 要求浏览器身份前后一致，这里不能伪装 UA
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // 站点页面整页藏掉，只留我们放验证框的那个 div
        final String inject = "(function(){if(window.__cfInit)return;window.__cfInit=1;window.__cf='';"
            + "var st=document.createElement('style');st.textContent='html,body{background:#fff!important;overflow:hidden!important}body>*:not(#__cfbox){display:none!important}#__cfbox{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:#fff}';"
            + "document.head.appendChild(st);"
            + "var b=document.createElement('div');b.id='__cfbox';document.body.appendChild(b);"
            + "var s=document.createElement('script');s.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';"
            + "s.onload=function(){try{turnstile.render(b,{sitekey:" + JSONObject.quote(sitekey) + ",theme:'light',callback:function(t){window.__cf=t;},'error-callback':function(c){window.__cf='ERR'+c;return true;}});window.__cfReady=1;}catch(e){window.__cf='ERR'+e.name;}};"
            + "s.onerror=function(){window.__cf='ERRnet';};"
            + "document.head.appendChild(s);})();";

        web.setWebViewClient(new WebViewClient() {
            boolean started;
            @Override
            public void onPageFinished(WebView view, String current) {
                if (started || !host(current).equals(host(url))) return;
                started = true;
                view.evaluateJavascript(inject, v -> view.setVisibility(View.VISIBLE));
                pollOne(web, label, shown, url);
            }
        });
        web.loadUrl(url);
        label.setText(shown + "  · 加载中…");
        web.postDelayed(() -> {
            if (!done && !tokens.has(url) && label.getText().toString().endsWith("加载中…")) label.setText(shown + "  · 验证框没加载出来");
        }, 20000);
        list.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));
    }

    private void pollOne(final WebView web, final TextView label, final String shown, final String url) {
        if (done || tokens.has(url)) return;
        // 第一位是「框已画出来」标记，后面是 token 或 ERR+错误代码
        web.evaluateJavascript("(window.__cfReady?'1':'0')+(window.__cf||'')", v -> {
            if (done || tokens.has(url)) return;
            String r = v == null ? "0" : v.replaceAll("^\"|\"$", "");
            if (!r.startsWith("0") && !r.startsWith("1")) r = "0";
            String t = r.substring(1);
            if (r.startsWith("1") && label.getText().toString().endsWith("加载中…")) label.setText(shown);
            if (t.startsWith("ERR")) {
                label.setText(shown + ("ERRnet".equals(t) ? "  · 连不上 Cloudflare" : "  · 验证出错（" + t.substring(3) + "）"));
                web.evaluateJavascript("window.__cf=''", null);
            }
            if (t.isEmpty() || t.startsWith("ERR")) {
                web.postDelayed(() -> pollOne(web, label, shown, url), 500);
                return;
            }
            tokens.put(url, t);
            label.setText(shown + "  ✓ 已通过");
            label.setTextColor(0xFF2E9E5B);
            got++;
            if (got >= total) web.postDelayed(this::finishAll, 400);
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
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        finishAll();
    }
}
