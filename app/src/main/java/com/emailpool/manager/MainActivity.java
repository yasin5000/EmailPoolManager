package com.emailpool.manager;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 1;
    private static final int REQ_FILE = 2;

    private WebView web;
    private final Runnable onQueueChanged = this::pushToWeb;
    private ValueCallback<Uri[]> filePickCallback;
    private long lastSync;
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-z0-9._%+'\\-]+@[a-z0-9\\-]+(\\.[a-z0-9\\-]+)*\\.[a-z]{2,}$");

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
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePickCallback != null) filePickCallback.onReceiveValue(null);
                filePickCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                try {
                    startActivityForResult(Intent.createChooser(i, "Select email|password list"), REQ_FILE);
                } catch (Exception e) {
                    filePickCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker app found", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (filePickCallback != null) {
            filePickCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
            filePickCallback = null;
        }
        if (uri != null) importFile(uri);
    }

    /** Reads a text file of "email|password" lines (one pair per line) and queues them. */
    private void importFile(Uri uri) {
        List<QueueStore.Pair> pairs = new ArrayList<>();
        int invalid = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                QueueStore.Pair p = parseLine(line);
                if (p != null) pairs.add(p); else if (!line.trim().isEmpty()) invalid++;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_LONG).show();
            return;
        }
        int added = QueueStore.get(this).addAll(pairs);
        QueueBus.changed();
        Toast.makeText(this, "Added " + added + " • " + (pairs.size() - added) + " duplicate(s) • "
                + invalid + " invalid line(s)", Toast.LENGTH_LONG).show();
    }

    /** Parses one "email|password" line. Returns null if the line has no valid email. */
    private QueueStore.Pair parseLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return null;
        String email, password = "";
        int bar = t.indexOf('|');
        if (bar >= 0) {
            email = t.substring(0, bar).trim().toLowerCase();
            password = t.substring(bar + 1).trim();
        } else {
            email = t.replaceAll("[,;\\s].*$", "").trim().toLowerCase();
        }
        email = email.replaceAll("^[<\"'(]+|[>\"')]+$", "");
        if (!EMAIL.matcher(email).matches()) return null;
        return new QueueStore.Pair(email, password);
    }

    @Override protected void onResume() {
        super.onResume();
        QueueBus.register(onQueueChanged);
        pushToWeb();
        web.onResume();
        syncInBackground();
        flushYes();
    }

    /** Retries YES emails that could not be written to the Google Sheet yet. */
    private void flushYes() {
        if (Tally.unsentCount(this) == 0) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> { Tally.flush(app); QueueBus.changed(); }).start();
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

    /** Lets the server know this phone is alive and refreshes the time left (at most every 30 s). */
    private void syncInBackground() {
        if (!LicenseServer.configured() || SystemClock.elapsedRealtime() - lastSync < 30_000) return;
        lastSync = SystemClock.elapsedRealtime();
        new Thread(() -> {
            LicenseManager.sync(getApplicationContext(), OverlayService.running);
            QueueBus.changed();
        }).start();
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

    /** Methods callable from the page as window.Native.* (run on a binder thread unless noted). */
    private class Bridge {

        @JavascriptInterface public String add(String raw) {
            List<QueueStore.Pair> pairs = new ArrayList<>();
            int invalid = 0;
            for (String line : raw.split("\\r?\\n")) {
                QueueStore.Pair p = parseLine(line);
                if (p != null) pairs.add(p); else if (!line.trim().isEmpty()) invalid++;
            }
            int added = QueueStore.get(MainActivity.this).addAll(pairs);
            QueueBus.changed();
            try {
                return new JSONObject().put("added", added)
                        .put("duplicates", pairs.size() - added).put("invalid", invalid).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String list(int limit) {
            List<QueueStore.Pair> l = QueueStore.get(MainActivity.this).list(limit);
            JSONArray arr = new JSONArray();
            for (QueueStore.Pair p : l) {
                JSONObject o = new JSONObject();
                try { o.put("email", p.email).put("password", p.password); } catch (Exception ignored) { }
                arr.put(o);
            }
            return arr.toString();
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

        @JavascriptInterface public String license() {
            LicenseManager.Status s = LicenseManager.status(MainActivity.this);
            try {
                return new JSONObject().put("state", s.state).put("lifetime", s.lifetime)
                        .put("expiry", s.expiryMs).put("remaining", s.remainingMs)
                        .put("server", LicenseServer.configured())
                        .put("device", LicenseManager.deviceLabel(MainActivity.this)).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Runs the (possibly slow) server check in the background; the page gets the answer in window.onActivate(). */
        @JavascriptInterface public String activate(String key) {
            final String k = key;
            new Thread(() -> {
                LicenseManager.Result r = LicenseManager.activate(getApplicationContext(), k);
                String json;
                try { json = new JSONObject().put("ok", r.ok).put("msg", r.msg).toString(); }
                catch (Exception e) { json = "{\"ok\":false,\"msg\":\"Error\"}"; }
                final String js = "window.onActivate&&window.onActivate(" + json + ")";
                runOnUiThread(() -> {
                    QueueBus.changed();   // refresh the notification / overlay
                    if (web != null) web.evaluateJavascript(js, null);
                });
            }).start();
            return "{\"pending\":true}";
        }

        // ---- YES / NO counter + Google Sheet ----

        @JavascriptInterface public String tally() { return Tally.snapshot(MainActivity.this).toString(); }

        @JavascriptInterface public void setName(String name) {
            Tally.setName(MainActivity.this, name);
            QueueBus.changed();
        }

        /** Returns {"ok":true} or {"ok":false,"msg":...}. Empty link clears the sheet. */
        @JavascriptInterface public String setSheet(String link, String tab) {
            String l = link == null ? "" : link.trim();
            try {
                if (l.isEmpty()) {
                    Tally.saveSheet(MainActivity.this, "", "", "");
                    QueueBus.changed();
                    return "{\"ok\":true}";
                }
                String id = Tally.parseSheetId(l);
                if (id == null) return "{\"ok\":false,\"msg\":\"That is not a Google Sheets link\"}";
                Tally.saveSheet(MainActivity.this, l, id, tab);
                QueueBus.changed();
                flushYes();
                return "{\"ok\":true}";
            } catch (Exception e) { return "{\"ok\":false,\"msg\":\"Error\"}"; }
        }

        @JavascriptInterface public void resetTally() {
            Tally.reset(MainActivity.this);
            QueueBus.changed();
        }

        @JavascriptInterface public void retryUpload() { flushYes(); }

        @JavascriptInterface public String clipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                if (d == null || d.getItemCount() == 0) return "";
                CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) { return ""; }
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
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            });
        }

        @JavascriptInterface public void startOverlay() {
            runOnUiThread(() -> {
                if (!LicenseManager.isActive(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Activate a key first", Toast.LENGTH_SHORT).show();
                    pushToWeb();
                    return;
                }
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
