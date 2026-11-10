package com.emailpool.manager;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView web;
    private final Runnable onQueueChanged = this::pushToWeb;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0F1A);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);          // file:///android_asset stays reachable
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onResume() {
        super.onResume();
        QueueBus.register(onQueueChanged);
        pushToWeb();
        web.onResume();
    }

    @Override protected void onPause() {
        QueueBus.unregister(onQueueChanged);
        web.onPause();   // the overlay service keeps running independently
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("Native"); web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        pushToWeb();
    }

    private void pushToWeb() {
        if (web != null) web.evaluateJavascript("window.onNative&&window.onNative()", null);
    }

    private boolean a11yEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(
                new ComponentName(this, EmailAccessibilityService.class).flattenToString().toLowerCase());
    }

    private boolean notifGranted() {
        return Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    /** Methods callable from the page as window.Native.* (run on a binder thread). */
    private class Bridge {
        private final Pattern EMAIL = Pattern.compile(
                "^[a-z0-9._%+'\\-]+@[a-z0-9\\-]+(\\.[a-z0-9\\-]+)*\\.[a-z]{2,}$");

        @JavascriptInterface public String add(String raw) {
            Set<String> valid = new LinkedHashSet<>();
            int invalid = 0;
            for (String tok : raw.split("[\\s,;]+")) {
                String t = tok.replaceAll("^[<\"'(]+|[>\"')]+$", "").toLowerCase();
                if (t.isEmpty()) continue;
                if (EMAIL.matcher(t).matches()) valid.add(t); else invalid++;
            }
            int added = QueueStore.get(MainActivity.this).addAll(new ArrayList<>(valid));
            QueueBus.changed();
            try {
                return new JSONObject().put("added", added)
                        .put("duplicates", valid.size() - added).put("invalid", invalid).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String list(int limit) {
            List<String> l = QueueStore.get(MainActivity.this).list(limit);
            return new JSONArray(l).toString();
        }

        @JavascriptInterface public void remove(String email) {
            QueueStore.get(MainActivity.this).remove(email);
            QueueBus.changed();
        }

        @JavascriptInterface public void clear() {
            QueueStore.get(MainActivity.this).clear();
            QueueBus.changed();
        }

        @JavascriptInterface public String status() {
            try {
                return new JSONObject()
                        .put("count", QueueStore.get(MainActivity.this).count())
                        .put("overlay", Settings.canDrawOverlays(MainActivity.this))
                        .put("a11y", a11yEnabled())
                        .put("notif", notifGranted())
                        .put("running", OverlayService.running).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void requestOverlay() { runOnUiThread(MainActivity.this::openOverlaySettings); }

        @JavascriptInterface public void openAccessibility() {
            runOnUiThread(() -> {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(MainActivity.this,
                        "Find “Email Pool” and turn it on", Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface public void requestNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            });
        }

        @JavascriptInterface public void startOverlay() {
            runOnUiThread(() -> {
                if (!Settings.canDrawOverlays(MainActivity.this)) { openOverlaySettings(); return; }
                if (!notifGranted()) requestNotifications();
                startForegroundService(new Intent(MainActivity.this, OverlayService.class));
                web.postDelayed(MainActivity.this::pushToWeb, 400);
            });
        }

        @JavascriptInterface public void stopOverlay() {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, OverlayService.class)
                        .setAction(OverlayService.ACTION_STOP);
                startService(i);
                web.postDelayed(MainActivity.this::pushToWeb, 400);
            });
        }
    }
}
