package com.emailpool.manager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YES / NO bookkeeping:
 *  - the email waiting for a decision ("pending"),
 *  - the YES / NO counters and the name they belong to,
 *  - the Google Sheet to write to and the list of YES emails not uploaded yet.
 *
 * Everything lives in SharedPreferences so it survives the service being restarted.
 * Uploading goes through the same Apps Script server as the license (action "yes").
 */
final class Tally {
    private static final String PREFS = "tally";
    private static final Object FLUSH_LOCK = new Object();
    private static final Pattern SHEET_ID = Pattern.compile("/d/([0-9A-Za-z_-]{20,})");
    private static final Pattern RAW_ID = Pattern.compile("^[0-9A-Za-z_-]{20,}$");

    private Tally() { }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ name + counters

    static String name(Context c) { return p(c).getString("name", ""); }

    static void setName(Context c, String n) {
        String t = n == null ? "" : n.trim().replaceAll("\\s+", " ");
        if (t.length() > 30) t = t.substring(0, 30);
        p(c).edit().putString("name", t).apply();
    }

    static int yes(Context c) { return p(c).getInt("yes", 0); }
    static int no(Context c) { return p(c).getInt("no", 0); }

    static synchronized void reset(Context c) {
        p(c).edit().putInt("yes", 0).putInt("no", 0).apply();
    }

    // ------------------------------------------------------------------ pending decision

    /** Email whose YES/NO decision is still open, or null. */
    static String pending(Context c) {
        String s = p(c).getString("pending", "");
        return s.isEmpty() ? null : s;
    }

    static void setPending(Context c, String email) {
        p(c).edit().putString("pending", email == null ? "" : email).apply();
    }

    /** YES: counts it, closes the pending item and queues the email for the Google Sheet. */
    static synchronized void decideYes(Context c, String email) {
        SharedPreferences sp = p(c);
        JSONArray a = unsent(sp);
        a.put(email);
        sp.edit().putInt("yes", sp.getInt("yes", 0) + 1).putString("pending", "")
                .putString("unsent", a.toString()).apply();
    }

    /** NO: counts it and closes the pending item. Nothing is saved to the sheet. */
    static synchronized void decideNo(Context c) {
        SharedPreferences sp = p(c);
        sp.edit().putInt("no", sp.getInt("no", 0) + 1).putString("pending", "").apply();
    }

    // ------------------------------------------------------------------ sheet settings

    static String sheetLink(Context c) { return p(c).getString("sheetLink", ""); }
    static String sheetId(Context c) { return p(c).getString("sheetId", ""); }
    static String sheetTab(Context c) { return p(c).getString("sheetTab", ""); }
    static String error(Context c) { return p(c).getString("err", ""); }

    /** Accepts a full Google Sheets link or just the id. Returns null if it is neither. */
    static String parseSheetId(String s) {
        if (s == null) return null;
        String t = s.trim();
        Matcher m = SHEET_ID.matcher(t);
        if (m.find()) return m.group(1);
        return RAW_ID.matcher(t).matches() ? t : null;
    }

    static void saveSheet(Context c, String link, String id, String tab) {
        String t = tab == null ? "" : tab.trim();
        if (t.length() > 80) t = t.substring(0, 80);
        p(c).edit().putString("sheetLink", link == null ? "" : link.trim())
                .putString("sheetId", id).putString("sheetTab", t).putString("err", "").apply();
    }

    // ------------------------------------------------------------------ upload queue

    private static JSONArray unsent(SharedPreferences sp) {
        try { return new JSONArray(sp.getString("unsent", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized int unsentCount(Context c) { return unsent(p(c)).length(); }

    private static synchronized String firstUnsent(Context c) {
        JSONArray a = unsent(p(c));
        return a.length() == 0 ? null : a.optString(0);
    }

    private static synchronized void dropFirst(Context c) {
        SharedPreferences sp = p(c);
        JSONArray a = unsent(sp);
        if (a.length() > 0) a.remove(0);
        sp.edit().putString("unsent", a.toString()).apply();
    }

    private static void setError(Context c, String msg) {
        p(c).edit().putString("err", msg == null ? "" : msg).apply();
    }

    /**
     * Uploads every waiting YES email to the sheet (email in column A, "yes" in column B).
     * Blocking: call from a background thread. Stops at the first problem and keeps the rest
     * queued, so nothing is lost when the phone is offline or the sheet link is wrong.
     * The server skips emails that are already in column A, so a retry never makes duplicates.
     */
    static void flush(Context ctx) {
        final Context c = ctx.getApplicationContext();
        synchronized (FLUSH_LOCK) {
            while (true) {
                String email = firstUnsent(c);
                if (email == null) { setError(c, ""); return; }
                if (!LicenseServer.configured()) { setError(c, "Server is not set in the app"); return; }
                String sid = sheetId(c);
                if (sid.isEmpty()) { setError(c, "Add your Google Sheet link first"); return; }
                String ids = LicenseManager.keyIds(c);
                if (ids.isEmpty()) { setError(c, "Activate a key first"); return; }

                Map<String, String> q = new LinkedHashMap<>();
                q.put("a", "yes"); q.put("k", ids); q.put("d", LicenseManager.deviceId(c));
                q.put("s", sid); q.put("t", sheetTab(c)); q.put("e", email);
                JSONObject r;
                try {
                    r = LicenseServer.get(q);
                } catch (IOException e) {
                    setError(c, "No connection — will retry automatically");
                    return;
                }
                if (r.optBoolean("ok")) { dropFirst(c); continue; }
                if ("bademail".equals(r.optString("code"))) { dropFirst(c); continue; }   // can never succeed
                setError(c, r.optString("msg", "Could not save to the sheet"));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ for the app screen

    static JSONObject snapshot(Context c) {
        try {
            String pend = pending(c);
            return new JSONObject().put("name", name(c)).put("yes", yes(c)).put("no", no(c))
                    .put("pending", pend == null ? "" : pend).put("unsent", unsentCount(c))
                    .put("sheet", sheetLink(c)).put("tab", sheetTab(c)).put("err", error(c));
        } catch (Exception e) { return new JSONObject(); }
    }
}
