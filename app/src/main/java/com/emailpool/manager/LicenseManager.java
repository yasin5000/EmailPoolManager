package com.emailpool.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Stores the activation state and answers "is the tool allowed to run, and for how long?". */
final class LicenseManager {
    static final int NONE = 0, ACTIVE = 1, EXPIRED = 2, CLOCK = 3;

    private static final String PREFS = "license";
    private static final long CLOCK_SLACK_MS = 5 * 60 * 1000L;   // tolerated backwards clock jump

    static final class Status {
        int state;
        boolean lifetime;
        long expiryMs;      // wall-clock end (0 for lifetime / none)
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

    /** Current state. Also records the latest time seen so rolling the phone clock back is caught. */
    static synchronized Status status(Context c) {
        SharedPreferences sp = p(c);
        long now = System.currentTimeMillis();
        long lastSeen = sp.getLong("lastSeen", 0);
        long expiry = sp.getLong("expiry", 0);      // 0 none, -1 lifetime, >0 end time
        Status s = new Status();

        if (expiry == 0) { s.state = NONE; return s; }
        if (now < lastSeen - CLOCK_SLACK_MS) { s.state = CLOCK; s.expiryMs = Math.max(expiry, 0); return s; }
        if (now > lastSeen) sp.edit().putLong("lastSeen", now).apply();

        if (expiry < 0) { s.state = ACTIVE; s.lifetime = true; return s; }
        s.expiryMs = expiry;
        if (now >= expiry) { s.state = EXPIRED; return s; }
        s.state = ACTIVE;
        s.remainingMs = expiry - now;
        return s;
    }

    static boolean isActive(Context c) { return status(c).state == ACTIVE; }

    /** Validates a key and applies it. Extra time stacks on top of any time still left. */
    static synchronized Result activate(Context c, String text) {
        LicenseKey k = LicenseKey.parse(text);
        if (k == null) return new Result(false, "Invalid key");

        SharedPreferences sp = p(c);
        Set<String> used = new HashSet<>(sp.getStringSet("used", new HashSet<>()));
        if (used.contains(k.id)) return new Result(false, "This key was already used");

        Status cur = status(c);
        if (cur.state == CLOCK) return new Result(false, "Phone clock looks wrong — fix the date & time first");

        long now = System.currentTimeMillis();
        long newExpiry;
        if (k.isLifetime()) {
            newExpiry = -1;
        } else if (cur.state == ACTIVE && cur.lifetime) {
            return new Result(false, "Lifetime access is already active");
        } else {
            long base = (cur.state == ACTIVE) ? cur.expiryMs : now;
            newExpiry = base + k.seconds * 1000L;
        }
        used.add(k.id);
        sp.edit().putStringSet("used", used)
                .putLong("expiry", newExpiry)
                .putLong("lastSeen", Math.max(now, sp.getLong("lastSeen", 0)))
                .apply();
        return new Result(true, k.isLifetime() ? "Activated: lifetime access" : "Activated");
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
