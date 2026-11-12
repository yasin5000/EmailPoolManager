package com.emailpool.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the activation state. With a server configured every key is locked to the first phone that
 * activates it (the server decides), time left comes from the server's clock and the server can
 * disable a key. Without a server it falls back to plain offline keys.
 */
final class LicenseManager {
    static final int NONE = 0, ACTIVE = 1, EXPIRED = 2, CLOCK = 3;

    private static final String PREFS = "license";
    private static final String APP_VERSION = "1";
    private static final long CLOCK_SLACK_MS = 5 * 60 * 1000L;   // tolerated backwards clock jump

    static final class Status {
        int state;
        boolean lifetime;
        long expiryMs;      // end time on the phone's clock (0 for lifetime / none)
        long remainingMs;   // 0 unless ACTIVE and not lifetime
    }

    static final class Result {
        final boolean ok; final String msg;
        Result(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }

    private LicenseManager() { }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ device identity

    /** Stable id of this phone (hash of ANDROID_ID), the same after reinstalling the app. */
    static String deviceId(Context c) {
        SharedPreferences sp = p(c);
        String id = sp.getString("devid", null);
        if (id != null) return id;
        String aid = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (aid == null || aid.isEmpty() || aid.equals("9774d56d682e549c")) aid = UUID.randomUUID().toString();
        id = sha256(aid + ":" + c.getPackageName()).substring(0, 16);
        sp.edit().putString("devid", id).apply();
        return id;
    }

    static String deviceName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String d = Build.MODEL == null ? "" : Build.MODEL;
        if (!m.isEmpty() && d.toLowerCase().startsWith(m.toLowerCase())) return d;
        return (m + " " + d).trim();
    }

    /** What the person reads out to you if you need to identify their phone. */
    static String deviceLabel(Context c) {
        String id = deviceId(c);
        return deviceName() + " • " + id.substring(id.length() - 6);
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    // ------------------------------------------------------------------ stored keys

    private static JSONArray readKeys(SharedPreferences sp) {
        try { return new JSONArray(sp.getString("keys", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static boolean hasKey(SharedPreferences sp, String id) {
        JSONArray a = readKeys(sp);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return true;
        return false;
    }

    private static void addKey(SharedPreferences sp, String id, long exp) {
        try {
            JSONArray a = readKeys(sp);
            a.put(new JSONObject().put("id", id).put("exp", exp));
            sp.edit().putString("keys", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Comma-separated ids of the keys stored on this phone (what the server expects in "k"). */
    static String keyIds(Context c) {
        JSONArray a = readKeys(p(c));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length() && i < 12; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            if (id.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ state

    /** Current state. Also records the latest time seen so rolling the phone clock back is caught. */
    static synchronized Status status(Context c) {
        SharedPreferences sp = p(c);
        JSONArray ks = readKeys(sp);
        Status s = new Status();
        if (ks.length() == 0) { s.state = NONE; return s; }

        long raw = System.currentTimeMillis();
        long lastSeen = sp.getLong("lastSeen", 0);
        if (raw < lastSeen - CLOCK_SLACK_MS) { s.state = CLOCK; return s; }
        if (raw > lastSeen) sp.edit().putLong("lastSeen", raw).apply();
        long now = raw + sp.getLong("offset", 0);               // corrected by the server's clock

        boolean life = false;
        long max = 0;
        for (int i = 0; i < ks.length(); i++) {
            long exp = ks.optJSONObject(i).optLong("exp");
            if (exp == -1) life = true; else if (exp > max) max = exp;
        }
        if (life) { s.state = ACTIVE; s.lifetime = true; return s; }
        s.expiryMs = max - sp.getLong("offset", 0);
        if (now >= max) { s.state = EXPIRED; return s; }
        s.state = ACTIVE;
        s.remainingMs = max - now;
        return s;
    }

    static boolean isActive(Context c) { return status(c).state == ACTIVE; }

    // ------------------------------------------------------------------ activation (blocking!)

    /** Validates a key and applies it. Needs internet when a server is configured. Not for the UI thread. */
    static Result activate(Context c, String text) {
        LicenseKey k = LicenseKey.parse(text);
        if (k == null) return new Result(false, "Invalid key");

        SharedPreferences sp = p(c);
        Status cur;
        synchronized (LicenseManager.class) {
            if (hasKey(sp, k.id)) return new Result(false, "This key is already active on this device");
            cur = status(c);
            if (cur.state == CLOCK) return new Result(false, "Phone clock looks wrong — fix the date & time first");
        }

        if (LicenseServer.configured()) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("a", "act"); q.put("k", k.id); q.put("d", deviceId(c));
            q.put("n", deviceName()); q.put("v", APP_VERSION);
            JSONObject r;
            try {
                r = LicenseServer.get(q);
            } catch (IOException e) {
                return new Result(false, "Can't reach the server — check your internet and try again");
            }
            if (!r.optBoolean("ok")) return new Result(false, r.optString("msg", "Activation failed"));
            long exp = r.optLong("exp");
            synchronized (LicenseManager.class) {
                if (r.has("now")) sp.edit().putLong("offset", r.optLong("now") - System.currentTimeMillis()).apply();
                addKey(sp, k.id, exp);
            }
            return new Result(true, exp == -1 ? "Activated: lifetime access" : "Activated");
        }

        // No server configured: plain offline key.
        synchronized (LicenseManager.class) {
            long exp;
            if (k.isLifetime()) {
                exp = -1;
            } else if (cur.state == ACTIVE && cur.lifetime) {
                return new Result(false, "Lifetime access is already active");
            } else {
                long base = (cur.state == ACTIVE) ? cur.expiryMs : System.currentTimeMillis();
                exp = base + k.seconds * 1000L;
            }
            addKey(sp, k.id, exp);
        }
        return new Result(true, k.isLifetime() ? "Activated: lifetime access" : "Activated");
    }

    // ------------------------------------------------------------------ sync with the server (blocking!)

    /**
     * Tells the server this phone is alive (so you can see it in keygen.html), refreshes the time left
     * and drops keys that were disabled, deleted or moved. Returns true if the server was reached.
     */
    static boolean sync(Context c, boolean toolRunning) {
        if (!LicenseServer.configured()) return false;
        SharedPreferences sp = p(c);
        JSONArray ks;
        synchronized (LicenseManager.class) { ks = readKeys(sp); }
        if (ks.length() == 0) return false;

        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < ks.length(); i++) {
            if (i > 0) ids.append(',');
            ids.append(ks.optJSONObject(i).optString("id"));
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("a", "ping"); q.put("k", ids.toString()); q.put("d", deviceId(c));
        q.put("n", deviceName()); q.put("v", APP_VERSION); q.put("r", toolRunning ? "1" : "0");
        JSONObject r;
        try {
            r = LicenseServer.get(q);
        } catch (IOException e) {
            return false;                                   // offline: keep working until the time runs out
        }
        if (!r.optBoolean("ok")) return false;
        JSONObject res = r.optJSONObject("keys");
        if (res == null) return false;

        synchronized (LicenseManager.class) {
            try {
                if (r.has("now")) sp.edit().putLong("offset", r.optLong("now") - System.currentTimeMillis()).apply();
                JSONArray keep = new JSONArray();
                JSONArray cur = readKeys(sp);
                for (int i = 0; i < cur.length(); i++) {
                    JSONObject o = cur.optJSONObject(i);
                    JSONObject info = res.optJSONObject(o.optString("id"));
                    if (info == null) { keep.put(o); continue; }
                    if ("ok".equals(info.optString("st"))) {
                        keep.put(new JSONObject().put("id", o.optString("id")).put("exp", info.optLong("exp")));
                    }                                        // anything else: disabled / deleted / moved -> drop it
                }
                sp.edit().putString("keys", keep.toString()).apply();
            } catch (Exception ignored) { }
        }
        return true;
    }

    /** "2d 3h", "45m 10s", "Lifetime" … for notifications. */
    static String describe(Status s) {
        if (s.state != ACTIVE) return "Not active";
        if (s.lifetime) return "Lifetime";
        long t = s.remainingMs / 1000;
        long d = t / 86400, h = (t % 86400) / 3600, m = (t % 3600) / 60, sec = t % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
