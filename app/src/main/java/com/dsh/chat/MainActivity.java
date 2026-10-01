package com.dsh.chat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * DSH 手机客户端：以原生 WebView 承载 DeepSeek Harness 的 Web 界面。
 *
 * <p>在 WebView 之外额外提供：任务完成通知（挂钩 DSH 的 api-session/status 事件）、
 * 后台保活前台服务、横竖屏适配、Cookie 持久化、原生文件选择器与下载支持。
 */
public class MainActivity extends Activity {

    /**
     * 默认服务地址。留空：首次启动会弹「连接 DSH」对话框让你填。
     * 想预置自己的地址就填这里，例如 "https://your-device.your-tailnet.ts.net/"。
     */
    private static final String DEFAULT_URL = "";
    private static final String PREFS = "dsh_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_NOTIFY = "notify_enabled";
    private static final String KEY_WIDE = "wide_mode";
    private static final String KEY_EXT_BASE = "ext_base";
    private static final String KEY_EXT_CSS = "ext_css";
    private static final String KEY_EXT_JS = "ext_js";
    /**
     * 远程扩展文件（界面修正热更新）的默认来源，应与 APK 下载页同一目录。
     * 留空则自动取当前服务器主机的 8443 端口。
     */
    private static final String DEFAULT_EXT_BASE = "";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF_PERM = 1002;

    private WebView webView;
    private ProgressBar progress;
    private TextView errorBanner;
    private ValueCallback<Uri[]> pendingFileCallback;
    private SharedPreferences prefs;
    private boolean promptedForAuth = false;

    /** 由文档启动脚本注入；不支持时回退到 onPageStarted 注入。 */
    private boolean useDocumentStartScript = false;
    private ScriptHandler scriptHandler;

    /** 当前处于「运行中」的会话集合，用于决定是否需要前台保活。 */
    private final Set<String> runningSessions = new HashSet<>();
    private boolean appInForeground = false;
    private boolean keepAliveStarted = false;
    private long lastDoneNotifyAt = 0L;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 允许通过 Chrome DevTools 协议远程调试 WebView（adb forward 后可在电脑上查 DOM/跑 JS）
        WebView.setWebContentsDebuggingEnabled(true);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        plp.gravity = Gravity.TOP;
        root.addView(progress, plp);

        errorBanner = new TextView(this);
        errorBanner.setBackgroundColor(Color.parseColor("#D93025"));
        errorBanner.setTextColor(Color.WHITE);
        errorBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        errorBanner.setPadding(dp(14), dp(10), dp(14), dp(10));
        errorBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.TOP;
        elp.topMargin = dp(3);
        root.addView(errorBanner, elp);

        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        Notifications.ensureChannels(this);
        applyCutoutMode();
        configureWebView();
        applyOrientationLayout(getResources().getConfiguration());
        requestNotificationPermissionIfNeeded();
        refreshRemoteExt();

        String saved = prefs.getString(KEY_URL, null);
        if (saved == null || saved.trim().isEmpty()) {
            showUrlDialog(true);
        } else {
            webView.loadUrl(saved);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ------------------------------------------------------------------ WebView 配置

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " DSHApp/1.1");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new Bridge(), "DSHNative");

        installTaskHook();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!useDocumentStartScript) {
                    // 回退路径：脚本内部有防重入，重复注入无副作用
                    view.evaluateJavascript(JsHook.SCRIPT, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                String targetHost = uri.getHost();
                String serverHost = Uri.parse(currentUrl()).getHost();
                if (targetHost != null && serverHost != null
                        && targetHost.equalsIgnoreCase(serverHost)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!useDocumentStartScript) {
                    view.evaluateJavascript(JsHook.SCRIPT, null);
                }
                injectExt(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showBanner("网络连接失败，请检查网络后重试");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int code = response == null ? 0 : response.getStatusCode();
                if (code == 401 || code == 403) {
                    showAuthPrompt();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                if (newProgress > 20) hideBanner();
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                } catch (Exception e) {
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String cookie = CookieManager.getInstance().getCookie(url);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                if (cookie != null) req.addRequestHeader("Cookie", cookie);
                if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                req.setMimeType(mimeType);
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(this, "已开始下载", Toast.LENGTH_SHORT).show();
                } else {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                }
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 在文档启动（页面脚本执行前）注入任务状态钩子，保证不会漏掉 WebSocket 创建。 */
    private void installTaskHook() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                        webView, JsHook.SCRIPT, Collections.singleton("*"));
                useDocumentStartScript = true;
                return;
            }
        } catch (Throwable ignored) {
            // 落到回退路径
        }
        useDocumentStartScript = false;
    }

    // ------------------------------------------------------------------ 横竖屏适配

    private void applyCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 已声明 configChanges，Activity 不会重建，WebView 保留会话；这里只调整系统栏与重排。
        applyOrientationLayout(newConfig);
        if (webView != null) {
            webView.requestLayout();
            webView.invalidate();
        }
    }

    /** 横屏时隐藏状态栏以争取纵向空间；竖屏恢复浅色状态栏。 */
    private void applyOrientationLayout(Configuration cfg) {
        Window w = getWindow();
        View decor = w.getDecorView();
        boolean landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;

        if (landscape) {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(landscape ? Color.TRANSPARENT : Color.WHITE);
        }
    }

    // ------------------------------------------------------------------ 通知 / 任务状态

    /** 页面 → 原生桥。 */
    private class Bridge {
        @JavascriptInterface
        public void onTaskState(String sessionId, String state) {
            final String sid = sessionId == null ? "" : sessionId;
            final String st = state == null ? "" : state;
            runOnUiThread(() -> handleTaskState(sid, st));
        }

        /** 供文档启动脚本查询是否启用宽屏模式。 */
        @JavascriptInterface
        public boolean isWideMode() {
            return prefs.getBoolean(KEY_WIDE, false);
        }
    }

    // ------------------------------------------------------------------ 远程扩展（热更新）

    /** 远程扩展来源：默认取当前服务器主机的 8443 端口（与 APK 下载页同目录）。 */
    private String extBase() {
        String configured = prefs.getString(KEY_EXT_BASE, null);
        if (configured != null && !configured.trim().isEmpty()) return configured;
        try {
            String host = Uri.parse(currentUrl()).getHost();
            if (host != null && !host.isEmpty()) return "https://" + host + ":8443/";
        } catch (Exception ignored) {
        }
        return DEFAULT_EXT_BASE;
    }

    /** 后台拉取 dsh-ext.css / dsh-ext.js 并缓存；内容有变化就立刻注入当前页面，避免要重启两次才生效。 */
    private void refreshRemoteExt() {
        final String base = extBase();
        if (base.isEmpty()) return;   // 还没配置服务器地址，跳过
        new Thread(() -> {
            String css = httpGetString(base + "dsh-ext.css");
            String js = httpGetString(base + "dsh-ext.js");
            if (css == null && js == null) return;
            String oldCss = prefs.getString(KEY_EXT_CSS, "");
            String oldJs = prefs.getString(KEY_EXT_JS, "");
            boolean changed = (css != null && !css.equals(oldCss))
                    || (js != null && !js.equals(oldJs));
            SharedPreferences.Editor e = prefs.edit();
            if (css != null) e.putString(KEY_EXT_CSS, css);
            if (js != null) e.putString(KEY_EXT_JS, js);
            e.apply();
            if (changed) {
                runOnUiThread(() -> {
                    if (webView != null) injectExt(webView);
                });
            }
        }, "dsh-ext-refresh").start();
    }

    private String httpGetString(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把缓存的远程 CSS/JS 注入当前页面。 */
    private void injectExt(WebView view) {
        String css = prefs.getString(KEY_EXT_CSS, "");
        String js = prefs.getString(KEY_EXT_JS, "");
        if (css != null && !css.isEmpty()) {
            String quoted = JSONObject.quote(css);
            view.evaluateJavascript(
                    "(function(){var s=document.getElementById('dsh-ext-css');"
                            + "if(!s){s=document.createElement('style');s.id='dsh-ext-css';"
                            + "(document.head||document.documentElement).appendChild(s);}"
                            + "s.textContent=" + quoted + ";})();", null);
        }
        if (js != null && !js.isEmpty()) {
            view.evaluateJavascript(js, null);
        }
    }

    private void handleTaskState(String sessionId, String state) {
        if ("start".equals(state)) {
            runningSessions.add(sessionId);
            // 任务一般是在前台发起的，此时启动前台服务不受 Android 12+ 后台启动限制
            startKeepAlive();
            return;
        }
        if (!"done".equals(state)) return;

        runningSessions.remove(sessionId);
        if (runningSessions.isEmpty()) stopKeepAlive();

        if (!notificationsEnabled() || appInForeground) return;

        // 多个会话同时结束时只提醒一次
        long now = System.currentTimeMillis();
        if (now - lastDoneNotifyAt < 2500L) return;
        lastDoneNotifyAt = now;
        Notifications.showTaskDone(this);
    }

    private boolean notificationsEnabled() {
        return prefs.getBoolean(KEY_NOTIFY, true);
    }

    private void startKeepAlive() {
        if (keepAliveStarted) return;
        keepAliveStarted = true;
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            i.setAction(KeepAliveService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            keepAliveStarted = false;
        }
    }

    private void stopKeepAlive() {
        if (!keepAliveStarted) return;
        keepAliveStarted = false;
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            i.setAction(KeepAliveService.ACTION_STOP);
            startService(i);
        } catch (Exception ignored) {
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIF_PERM
                && results.length > 0
                && results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予通知权限，任务完成时将无法提醒", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 地址设置 / 提示

    private String currentUrl() {
        String u = prefs.getString(KEY_URL, DEFAULT_URL);
        return u == null ? "" : u.trim();
    }

    private void showBanner(String text) {
        errorBanner.setText(text);
        errorBanner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        if (errorBanner.getVisibility() == View.VISIBLE) {
            errorBanner.setVisibility(View.GONE);
        }
    }

    private void showAuthPrompt() {
        if (promptedForAuth) return;
        promptedForAuth = true;
        new AlertDialog.Builder(this)
                .setTitle("需要重新登录")
                .setMessage("登录状态已失效。请粘贴新的带 token 的访问链接（形如 …/?token=xxxx）。")
                .setCancelable(true)
                .setPositiveButton("去粘贴", (d, w) -> {
                    promptedForAuth = false;
                    showUrlDialog(false);
                })
                .setNegativeButton("稍后", (d, w) -> promptedForAuth = false)
                .show();
    }

    private void showUrlDialog(boolean firstRun) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(currentUrl());
        input.setSelectAllOnFocus(true);
        input.setSingleLine(false);
        input.setMinLines(2);
        int pad = dp(16);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "连接 DSH" : "设置")
                .setMessage("填写 DSH 访问地址。\n首次请粘贴带 token 的完整链接，认证成功后会自动跳转。")
                .setView(wrap)
                .setPositiveButton("连接", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (v.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请填写 DSH 访问地址", Toast.LENGTH_SHORT).show();
                        webView.postDelayed(() -> showUrlDialog(firstRun), 250);
                        return;
                    }
                    prefs.edit().putString(KEY_URL, v).apply();
                    promptedForAuth = false;
                    hideBanner();
                    webView.loadUrl(v);
                    refreshRemoteExt();
                })
                .setNegativeButton(firstRun ? "退出" : "取消", (d, w) -> {
                    if (firstRun) finish();
                })
                .setNeutralButton("任务提醒", (d, w) -> showNotifyDialog());
        b.show();
    }

    private void showNotifyDialog() {
        boolean enabled = notificationsEnabled();
        new AlertDialog.Builder(this)
                .setTitle("任务完成提醒")
                .setMessage("开启后，当你在别的应用里、而 DSH 智能体跑完任务时，会收到通知。\n\n"
                        + "当前状态：" + (enabled ? "已开启" : "已关闭"))
                .setPositiveButton(enabled ? "关闭提醒" : "开启提醒", (d, w) -> {
                    boolean next = !enabled;
                    prefs.edit().putBoolean(KEY_NOTIFY, next).apply();
                    Toast.makeText(this, next ? "已开启任务完成提醒" : "已关闭任务完成提醒",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("发一条测试通知", (d, w) -> {
                    Notifications.showTaskDone(this);
                    Toast.makeText(this, "已发送测试通知", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("返回", null)
                .show();
    }

    // ------------------------------------------------------------------ 其它回调

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (pendingFileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                pendingFileCallback.onReceiveValue(results);
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        final boolean wide = prefs.getBoolean(KEY_WIDE, false);
        new AlertDialog.Builder(this)
                .setTitle("DSH")
                .setItems(new CharSequence[]{
                        "重新加载",
                        "设置 / 任务提醒",
                        wide ? "关闭宽屏模式（恢复手机布局）" : "开启宽屏模式（设置面板更完整）",
                        "刷新界面修正（热更新，无需重装）",
                        "退出"
                }, (d, which) -> {
                    if (which == 0) {
                        hideBanner();
                        if (currentUrl().isEmpty()) showUrlDialog(false);
                        else webView.loadUrl(currentUrl());
                    } else if (which == 1) {
                        showUrlDialog(false);
                    } else if (which == 2) {
                        prefs.edit().putBoolean(KEY_WIDE, !wide).apply();
                        Toast.makeText(this, !wide ? "已开启宽屏模式，重载中…" : "已恢复手机布局，重载中…",
                                Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 3) {
                        refreshRemoteExt();
                        Toast.makeText(this, "正在拉取界面修正…", Toast.LENGTH_SHORT).show();
                        webView.postDelayed(() -> {
                            injectExt(webView);
                            webView.reload();
                        }, 3000);
                    } else {
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appInForeground = true;
        // 没有任务在跑就无需保活；有任务则继续，避免频繁起停
        if (runningSessions.isEmpty()) stopKeepAlive();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        appInForeground = false;
        if (webView != null) webView.onPause();
        // 保活服务已在任务开始时启动（那时处于前台），此处不再启动，避免后台启动限制
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (scriptHandler != null) {
            try {
                scriptHandler.remove();
            } catch (Exception ignored) {
            }
            scriptHandler = null;
        }
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.removeJavascriptInterface("DSHNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
