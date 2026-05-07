package com.sinanjams.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1003;
    private static final String PREFS_NAME = "10kolikler_prefs";
    private static final String PREF_PENDING_SRT = "pending_ai_srt";
    private static final String PREF_GROQ_API_KEY = "groq_api_key";
    private static final String PREF_PREMIUM_LIMIT_OVERRIDE = "premium_limit_override";
    private static final String UPDATE_OWNER = "Sinanjam";
    private static final String UPDATE_REPO = "altyazi-araci";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri lastSelectedVideoUri;
    private Uri lastSelectedLogoUri;
    private String pendingChooserKind = "file";

    private final BroadcastReceiver aiSrtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AiSrtService.ACTION_SRT_READY.equals(intent.getAction())) return;
            String srt = intent.getStringExtra(AiSrtService.EXTRA_SRT_TEXT);
            if (srt != null) {
                loadSrtIntoWebView(srt);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);
        keepFullScreen();
        requestNotificationPermissionIfNeeded();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    WebChromeClient.FileChooserParams params
            ) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                pendingChooserKind = detectChooserKind(params);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                if ("video".equals(pendingChooserKind)) {
                    intent.setType("video/*");
                } else if ("image".equals(pendingChooserKind)) {
                    intent.setType("image/*");
                } else {
                    intent.setType("*/*");
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, "Dosya seç"), FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Dosya seçici açılamadı", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/www/videocu.html");
        registerAiSrtReceiver();
    }

    private void registerAiSrtReceiver() {
        IntentFilter filter = new IntentFilter(AiSrtService.ACTION_SRT_READY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(aiSrtReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(aiSrtReceiver, filter);
        }
    }

    private void loadPendingSrtIfExists() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String srt = prefs.getString(PREF_PENDING_SRT, null);
        if (srt != null && !srt.isEmpty()) {
            prefs.edit().remove(PREF_PENDING_SRT).apply();
            loadSrtIntoWebView(srt);
        }
    }

    private void loadSrtIntoWebView(String srt) {
        if (webView == null || srt == null) return;
        String quoted = JSONObject.quote(srt);
        runOnUiThread(() -> webView.evaluateJavascript("window.loadAiSrt && window.loadAiSrt(" + quoted + ");", null));
    }

    private String detectChooserKind(WebChromeClient.FileChooserParams params) {
        String[] acceptTypes = params.getAcceptTypes();
        if (acceptTypes != null) {
            for (String type : acceptTypes) {
                String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
                if (lower.contains("video")) return "video";
                if (lower.contains("image")) return "image";
            }
        }
        return "file";
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void keepFullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepFullScreen();
        loadPendingSrtIfExists();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if (results != null && results.length > 0 && results[0] != null) {
                Uri selected = results[0];
                try {
                    final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(selected, flags);
                } catch (Exception ignored) {
                    // Temporary URI grants are still enough while the app process is alive.
                }
                if ("video".equals(pendingChooserKind)) {
                    lastSelectedVideoUri = selected;
                } else if ("image".equals(pendingChooserKind)) {
                    lastSelectedLogoUri = selected;
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            pendingChooserKind = "file";
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(aiSrtReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }


    private void postUpdateResult(String message) {
        if (webView == null) return;
        String quoted = JSONObject.quote(message == null ? "" : message);
        runOnUiThread(() -> webView.evaluateJavascript("window.onUpdateCheckResult && window.onUpdateCheckResult(" + quoted + ");", null));
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int getAppVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            }
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void openUrl(String url) {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                postUpdateResult("Güncelleme bulundu ama GitHub açılamadı: " + e.getMessage());
            }
        });
    }

    private void checkLatestRelease() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.github.com/repos/" + UPDATE_OWNER + "/" + UPDATE_REPO + "/releases/latest");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Altyazi-Araci-Android");
            int code = conn.getResponseCode();
            if (code == 404) {
                postUpdateResult("GitHub'da henüz yayınlanmış release bulunamadı.");
                return;
            }
            if (code < 200 || code >= 300) {
                postUpdateResult("Güncelleme kontrol edilemedi. GitHub yanıtı: " + code);
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();

            JSONObject release = new JSONObject(body.toString());
            String tag = release.optString("tag_name", "").trim();
            String releaseName = release.optString("name", tag).trim();
            String htmlUrl = release.optString("html_url", "https://github.com/" + UPDATE_OWNER + "/" + UPDATE_REPO + "/releases/latest");
            String currentName = getAppVersionName();
            int cmp = compareVersionTags(tag, currentName);
            if (cmp > 0 || (tag.length() > 0 && currentName.length() > 0 && !normalizeVersion(tag).equals(normalizeVersion(currentName)) && cmp == 0)) {
                postUpdateResult("Yeni sürüm bulundu: " + (releaseName.length() > 0 ? releaseName : tag) + ". GitHub açılıyor; APK'yı indirip eski uygulamayı kaldırmadan üstüne kur.");
                openUrl(htmlUrl);
            } else {
                postUpdateResult("Güncelsin. Yüklü sürüm: " + currentName + " (code " + getAppVersionCode() + ").");
            }
        } catch (Exception e) {
            postUpdateResult("Güncelleme kontrol edilemedi: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String normalizeVersion(String value) {
        if (value == null) return "";
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("v")) v = v.substring(1);
        v = v.replaceAll("[^0-9.].*$", "");
        return v;
    }

    private static int compareVersionTags(String latestTag, String currentVersionName) {
        String latest = normalizeVersion(latestTag);
        String current = normalizeVersion(currentVersionName);
        if (latest.isEmpty() || current.isEmpty()) return 0;
        String[] a = latest.split("\\.");
        String[] b = current.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = i < a.length ? parseVersionPart(a[i]) : 0;
            int bi = i < b.length ? parseVersionPart(b[i]) : 0;
            if (ai != bi) return ai > bi ? 1 : -1;
        }
        return 0;
    }

    private static int parseVersionPart(String part) {
        try { return Integer.parseInt(part.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String getSavedGroqApiKey() {
            try {
                return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_API_KEY, "");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveGroqApiKey(String groqApiKey) {
            try {
                String key = groqApiKey == null ? "" : groqApiKey.trim();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_GROQ_API_KEY, key).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void clearGroqApiKey() {
            try {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_GROQ_API_KEY).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean getPremiumLimitOverride() {
            try {
                return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_PREMIUM_LIMIT_OVERRIDE, false);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void savePremiumLimitOverride(boolean enabled) {
            try {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_PREMIUM_LIMIT_OVERRIDE, enabled).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean checkForUpdates() {
            try {
                new Thread(() -> checkLatestRelease(), "UpdateCheckThread").start();
                return true;
            } catch (Exception e) {
                postUpdateResult("Güncelleme kontrolü başlatılamadı: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return false;
            }
        }

        @JavascriptInterface
        public boolean startBackgroundMp4Export(String settingsJson) {
            try {
                if (lastSelectedVideoUri == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Önce video seç", Toast.LENGTH_LONG).show());
                    return false;
                }
                requestNotificationPermissionIfNeeded();
                Intent intent = new Intent(MainActivity.this, ExportService.class);
                intent.setAction(ExportService.ACTION_START_EXPORT);
                intent.putExtra(ExportService.EXTRA_VIDEO_URI, lastSelectedVideoUri.toString());
                if (lastSelectedLogoUri != null) {
                    intent.putExtra(ExportService.EXTRA_LOGO_URI, lastSelectedLogoUri.toString());
                }
                intent.putExtra(ExportService.EXTRA_SETTINGS_JSON, settingsJson == null ? "{}" : settingsJson);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                return true;
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "başlatılamadı" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "MP4 başlatılamadı: " + message, Toast.LENGTH_LONG).show());
                return false;
            }
        }

        @JavascriptInterface
        public boolean startAiSrt(String groqApiKey, boolean premiumLimitOverride) {
            try {
                if (lastSelectedVideoUri == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Önce video seç", Toast.LENGTH_LONG).show());
                    return false;
                }
                if (groqApiKey == null || groqApiKey.trim().isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Groq API Key gir", Toast.LENGTH_LONG).show());
                    return false;
                }
                requestNotificationPermissionIfNeeded();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_GROQ_API_KEY, groqApiKey.trim())
                        .putBoolean(PREF_PREMIUM_LIMIT_OVERRIDE, premiumLimitOverride)
                        .apply();
                Intent intent = new Intent(MainActivity.this, AiSrtService.class);
                intent.setAction(AiSrtService.ACTION_START_AI_SRT);
                intent.putExtra(AiSrtService.EXTRA_VIDEO_URI, lastSelectedVideoUri.toString());
                intent.putExtra(AiSrtService.EXTRA_API_KEY, groqApiKey.trim());
                intent.putExtra(AiSrtService.EXTRA_SKIP_SIZE_LIMIT, premiumLimitOverride);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                return true;
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "başlatılamadı" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "AI SRT başlatılamadı: " + message, Toast.LENGTH_LONG).show());
                return false;
            }
        }
    }
}
