package io.github.macapex.aidailyradar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends Activity {

    static final String HOME_URL = "https://macapex.github.io/ai-daily-radar/";
    static final String HOME_HOST = "macapex.github.io";
    static final String OFFLINE_URL = "file:///android_asset/offline.html";
    static final long STALE_MS = 15L * 60L * 1000L; // refresh on resume after 15 min
    static final TimeZone REPORT_TZ = TimeZone.getTimeZone("Asia/Shanghai");

    private WebView web;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;
    private long lastLoadAt = 0L; // wall clock (System.currentTimeMillis)
    private String failedUrl = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge: draw behind system bars, pad content by the insets.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat ctl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctl.setAppearanceLightStatusBars(false);
        ctl.setAppearanceLightNavigationBars(false);

        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        web = findViewById(R.id.web);
        swipe = findViewById(R.id.swipe);
        progress = findViewById(R.id.progress);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " AIDailyRadarApp/" + BuildConfigLite.VERSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.setForceDark(WebSettings.FORCE_DARK_OFF);
        applyCacheMode();

        // GPU-backed layer so WebGL / the 3D hero renders smoothly.
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setBackgroundColor(Color.parseColor("#07080D"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                if (OFFLINE_URL.equals(url)) web.clearHistory();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline(req.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showOffline(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                if (p >= 100) progress.setVisibility(View.GONE);
            }
        });

        swipe.setColorSchemeColors(Color.parseColor("#8B7BFF"), Color.parseColor("#5EEAD4"));
        swipe.setProgressBackgroundColorSchemeColor(Color.parseColor("#151726"));
        // Only allow pull-to-refresh when the page itself is scrolled to the top.
        swipe.setOnChildScrollUpCallback((parent, child) -> web.getScrollY() > 0);
        swipe.setOnRefreshListener(this::refresh);

        // Never restore a saved WebView (it would show yesterday's page after process death);
        // always load fresh: the deep link, else the last internal page, else home.
        Uri data = getIntent() != null ? getIntent().getData() : null;
        String start = HOME_URL;
        if (data != null && isInternal(data) && !"file".equals(data.getScheme())) {
            start = data.toString();
        } else if (savedInstanceState != null) {
            String saved = savedInstanceState.getString("currentUrl");
            if (saved != null && saved.startsWith("https://") && isInternal(Uri.parse(saved))) start = saved;
        }
        loadFresh(start);
    }

    private boolean isInternal(Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme) || "about".equals(scheme) || "data".equals(scheme) || "javascript".equals(scheme)) return true;
        return HOME_HOST.equalsIgnoreCase(uri.getHost());
    }

    private boolean handleUrl(Uri uri) {
        if (isInternal(uri)) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) { }
        return true;
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        @SuppressWarnings("deprecation") NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    /** Online: always hit the network (no stale HTML/JSON). Offline: use cache, even stale. */
    private void applyCacheMode() {
        web.getSettings().setCacheMode(isOnline()
                ? WebSettings.LOAD_NO_CACHE
                : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    private static String dayOf(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(REPORT_TZ);
        return f.format(new Date(millis));
    }

    private static String localDayOf(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date(millis));
    }

    /** Stale after 15 min, a calendar-day change (Asia/Shanghai or device-local), or a clock jump back. */
    private boolean isStale() {
        if (lastLoadAt <= 0) return true;
        long now = System.currentTimeMillis();
        if (now - lastLoadAt > STALE_MS || now < lastLoadAt) return true;
        return !dayOf(now).equals(dayOf(lastLoadAt)) || !localDayOf(now).equals(localDayOf(lastLoadAt));
    }

    /** Loads the URL forcing the HTML document to be revalidated with the server. */
    private void loadFresh(String url) {
        applyCacheMode();
        failedUrl = null;
        Map<String, String> headers = new HashMap<>();
        if (isOnline()) {
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
        }
        web.loadUrl(url, headers);
        lastLoadAt = System.currentTimeMillis();
    }

    private void refresh() {
        String cur = web.getUrl();
        if (failedUrl != null) {
            loadFresh(failedUrl);
        } else if (cur == null || OFFLINE_URL.equals(cur)) {
            loadFresh(HOME_URL);
        } else {
            loadFresh(cur);
        }
    }

    private void showOffline(String url) {
        if (url != null && !url.startsWith("file:")) failedUrl = url;
        swipe.setRefreshing(false);
        web.stopLoading();
        web.loadUrl(OFFLINE_URL);
    }

    final class Bridge {
        @JavascriptInterface
        public void retry() {
            web.post(MainActivity.this::refresh);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (isStale()) refresh();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent.getData();
        if (data != null && isInternal(data)) loadFresh(data.toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // Only remember which internal page was open; the page itself is always reloaded fresh.
        String cur = failedUrl != null ? failedUrl : (web != null ? web.getUrl() : null);
        if (cur != null && cur.startsWith("https://")) out.putString("currentUrl", cur);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack() && !OFFLINE_URL.equals(web.getUrl())) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    static final class BuildConfigLite { static final String VERSION = "1.0.1"; }
}
