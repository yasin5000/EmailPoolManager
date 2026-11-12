package com.emailpool.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service that owns the draggable always-on-top 4-button overlay. */
public class OverlayService extends Service {
    static final String ACTION_STOP = "com.emailpool.manager.STOP";
    static volatile boolean running;

    private static final String CHANNEL = "overlay";
    private static final int NOTIF_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onQueueChanged = this::refresh;
    private final Runnable ticker = this::tick;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long lastSyncAt;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private DragLayout root;
    private TextView countView, handleView;
    private LinearLayout counterBox, gridBox;
    private GradientDrawable rootBg;
    private boolean collapsed;
    private Button copyMailBtn, copyPassBtn, pasteMailBtn, pastePassBtn, yesBtn, noBtn;
    private TextView statsView, pendView;
    private long lastTap;
    private SharedPreferences prefs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("overlay", MODE_PRIVATE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Floating tool", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Must be called promptly after startForegroundService().
        Notification n = buildNotification(QueueStore.get(this).count());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (!LicenseManager.isActive(this)) {
            Toast.makeText(this, "Activation key needed or expired", Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root == null) createOverlay();
        uploadYes(false);                                       // retry YES emails that could not be saved earlier
        QueueBus.register(onQueueChanged);
        running = true;
        refresh();
        main.removeCallbacks(ticker);
        main.post(ticker);
        return START_STICKY;   // system restarts the tool if the process is killed
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (root != null) root.post(() -> root.clampAndApply());
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacks(ticker);
        if (LicenseServer.configured()) {                       // tell the server the tool is no longer running
            final android.content.Context app = getApplicationContext();
            io.execute(() -> LicenseManager.sync(app, false));
        }
        io.shutdown();
        QueueBus.unregister(onQueueChanged);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- license

    /** Keeps the notification's time-left fresh and shuts the tool down the moment the key runs out. */
    private void tick() {
        LicenseManager.Status s = LicenseManager.status(this);
        if (s.state != LicenseManager.ACTIVE) { expire(); return; }
        long nowMs = SystemClock.elapsedRealtime();
        if (LicenseServer.configured() && (lastSyncAt == 0 || nowMs - lastSyncAt >= 5 * 60_000L)) {
            lastSyncAt = nowMs;                                  // heartbeat: shows 'running' in keygen.html, applies disable/move
            final android.content.Context app = getApplicationContext();
            io.execute(() -> LicenseManager.sync(app, true));
        }
        refresh();
        long next = s.lifetime ? 60_000L : Math.min(15_000L, s.remainingMs + 50);
        main.postDelayed(ticker, Math.max(next, 500));
    }

    private void expire() {
        Toast.makeText(this, "Activation expired — enter a new key", Toast.LENGTH_LONG).show();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        QueueBus.changed();
    }

    /** Every action re-checks the license so an expired key can't be used even for one more tap. */
    private boolean licensed() {
        if (LicenseManager.isActive(this)) return true;
        expire();
        return false;
    }

    // ---------------------------------------------------------------- notification

    private String timeLeft() {
        LicenseManager.Status ls = LicenseManager.status(this);
        return LicenseManager.describe(ls) + (ls.lifetime ? "" : " left");
    }

    private Notification buildNotification(int count) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Email Pool is active")
                .setContentText(count + " pair" + (count == 1 ? "" : "s") + " remaining • " + timeLeft())
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void refresh() {
        int n = QueueStore.get(this).count();
        if (countView != null) {
            String t = n >= 10000 ? (n / 1000) + "k" : String.valueOf(n);
            countView.setText(t);
            if (handleView != null) handleView.setText(t);
            float a = n > 0 ? 1f : 0.45f;
            copyMailBtn.setAlpha(a);
            copyPassBtn.setAlpha(a);
            String nm = Tally.name(this), pend = Tally.pending(this);
            int un = Tally.unsentCount(this);
            statsView.setText((nm.isEmpty() ? "No name" : nm) + "\n✔ " + Tally.yes(this) + "   ✘ " + Tally.no(this)
                    + (un > 0 ? "   ⇡" + un : ""));
            pendView.setText(pend == null ? "Pending: none" : "Pending:\n" + pend);
            pendView.setTextColor(pend == null ? 0x99FFFFFF : 0xFFFBBF24);
            yesBtn.setAlpha(pend == null ? 0.45f : 1f);
            noBtn.setAlpha(pend == null ? 0.45f : 1f);
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(n));
    }

    // ---------------------------------------------------------------- actions

    private boolean debounced() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTap < 250) return true;  // ignore accidental double taps
        lastTap = now;
        return false;
    }

    private void doCopyMail() {
        if (!licensed() || debounced()) return;
        QueueStore.Pair p = QueueStore.get(this).peek();
        if (p == null) { Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show(); return; }
        QueueStore.lastEmail = p.email;
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("email", p.email));
        flash(copyMailBtn, "COPIED", "COPY\nMAIL");
    }

    private void doCopyPass() {
        if (!licensed() || debounced()) return;
        QueueStore.Pair p = QueueStore.get(this).peek();
        if (p == null) { Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show(); return; }
        QueueStore.lastEmail = p.email;
        QueueStore.lastPassword = p.password;
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("password", p.password));
        flash(copyPassBtn, "COPIED", "COPY\nPASS");
        QueueStore.get(this).remove(p.email);   // pair is done once its password has been taken
        String old = Tally.pending(this);
        Tally.setPending(this, p.email);        // now waiting for YES / NO
        if (old != null && !old.equals(p.email))
            Toast.makeText(this, "Previous pending (" + old + ") was skipped", Toast.LENGTH_SHORT).show();
        QueueBus.changed();
    }

    /** YES: counts it and saves the email (+ "yes") into the Google Sheet. NO: only counts it. */
    private void doDecide(boolean yes) {
        if (!licensed() || debounced()) return;
        String pend = Tally.pending(this);
        if (pend == null) { Toast.makeText(this, "Nothing pending — copy a password first", Toast.LENGTH_SHORT).show(); return; }
        if (yes) {
            Tally.decideYes(this, pend);
            if (Tally.sheetId(this).isEmpty())
                Toast.makeText(this, "YES counted — add your Google Sheet link in the app to save it", Toast.LENGTH_LONG).show();
        } else {
            Tally.decideNo(this);
        }
        QueueBus.changed();
        if (yes) uploadYes(true);
    }

    /** Sends waiting YES emails to the sheet in the background; keeps them queued if it fails. */
    private void uploadYes(final boolean report) {
        if (Tally.unsentCount(this) == 0) return;
        final Context app = getApplicationContext();
        try {
            io.execute(() -> {
                Tally.flush(app);
                main.post(() -> {
                    QueueBus.changed();
                    String err = Tally.error(app);
                    if (report && !err.isEmpty() && !Tally.sheetId(app).isEmpty())
                        Toast.makeText(app, err, Toast.LENGTH_LONG).show();
                });
            });
        } catch (Exception ignored) { }          // executor already shut down
    }

    private void doPaste(String text, String emptyMsg, Button btn, String temp, String normal) {
        if (!licensed()) return;
        if (text == null) { Toast.makeText(this, emptyMsg, Toast.LENGTH_SHORT).show(); return; }
        EmailAccessibilityService s = EmailAccessibilityService.instance;
        if (s == null) {
            Toast.makeText(this, "Turn on Email Pool in Accessibility settings to enable PASTE", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
            return;
        }
        // Put the exact field we want on the clipboard first, since the clipboard may currently
        // hold the *other* field (e.g. password was copied after email).
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("value", text));
        switch (s.pasteIntoFocused(text)) {
            case EmailAccessibilityService.OK: flash(btn, temp, normal); break;
            case EmailAccessibilityService.NO_FIELD:
                Toast.makeText(this, "Tap a text field first", Toast.LENGTH_SHORT).show(); break;
            default:
                Toast.makeText(this, "This field doesn’t accept pasting", Toast.LENGTH_SHORT).show();
        }
    }

    private void flash(Button b, String temp, String normal) {
        b.setText(temp);
        main.postDelayed(() -> b.setText(normal), 700);
    }

    // ---------------------------------------------------------------- overlay UI

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable grad(int c1, int c2, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c1, c2});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button makeButton(String label, int c1, int c2, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(10f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.02f);
        b.setLineSpacing(0, 0.95f);
        b.setGravity(Gravity.CENTER);
        b.setStateListAnimator(null);
        b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), grad(c1, c2, 14), null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(64), dp(38));
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(p);
        return b;
    }

    private void createOverlay() {
        root = new DragLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(8), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2121826);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x33FFFFFF);
        rootBg = bg;
        root.setBackground(bg);
        root.setElevation(dp(8));

        LinearLayout counter = new LinearLayout(this);       // drag handle + remaining count
        counterBox = counter;
        counter.setOrientation(LinearLayout.VERTICAL);
        counter.setGravity(Gravity.CENTER);
        counter.setMinimumWidth(dp(34));
        countView = new TextView(this);
        countView.setTextColor(0xFFFFFFFF);
        countView.setTextSize(15f);
        countView.setTypeface(Typeface.DEFAULT_BOLD);
        countView.setGravity(Gravity.CENTER);
        TextView cap = new TextView(this);
        cap.setText("LEFT");
        cap.setTextColor(0x99FFFFFF);
        cap.setTextSize(8f);
        cap.setLetterSpacing(0.08f);
        cap.setGravity(Gravity.CENTER);
        counter.addView(countView);
        counter.addView(cap);
        root.addView(counter);

        LinearLayout grid = new LinearLayout(this);
        gridBox = grid;
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        copyMailBtn = makeButton("COPY\nMAIL", 0xFF6366F1, 0xFF4F46E5, v -> doCopyMail());
        copyPassBtn = makeButton("COPY\nPASS", 0xFF8B5CF6, 0xFF7C3AED, v -> doCopyPass());
        row1.addView(copyMailBtn);
        row1.addView(copyPassBtn);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        pasteMailBtn = makeButton("PASTE\nMAIL", 0xFF0EA5A4, 0xFF0D9488, v ->
                doPaste(QueueStore.lastEmail, "Copy an email first", pasteMailBtn, "PASTED", "PASTE\nMAIL"));
        pastePassBtn = makeButton("PASTE\nPASS", 0xFF22C55E, 0xFF16A34A, v ->
                doPaste(QueueStore.lastPassword, "Copy a password first", pastePassBtn, "PASTED", "PASTE\nPASS"));
        row2.addView(pasteMailBtn);
        row2.addView(pastePassBtn);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        yesBtn = makeButton("YES", 0xFF16A34A, 0xFF15803D, v -> doDecide(true));
        noBtn = makeButton("NO", 0xFFEF4444, 0xFFDC2626, v -> doDecide(false));
        row3.addView(yesBtn);
        row3.addView(noBtn);

        statsView = new TextView(this);
        statsView.setTextColor(0xFFFFFFFF);
        statsView.setTextSize(10f);
        statsView.setTypeface(Typeface.DEFAULT_BOLD);
        statsView.setGravity(Gravity.CENTER);
        statsView.setSingleLine(false);
        statsView.setMaxLines(2);
        statsView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statsView.setLayoutParams(new LinearLayout.LayoutParams(dp(134), LinearLayout.LayoutParams.WRAP_CONTENT));
        pendView = new TextView(this);
        pendView.setTextSize(9f);
        pendView.setGravity(Gravity.CENTER);
        pendView.setMaxLines(2);
        pendView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pendView.setLayoutParams(new LinearLayout.LayoutParams(dp(134), LinearLayout.LayoutParams.WRAP_CONTENT));

        grid.addView(statsView);
        grid.addView(row1);
        grid.addView(row2);
        grid.addView(pendView);
        grid.addView(row3);
        root.addView(grid);

        // Small round handle shown while the tool is hidden; tap it to bring the buttons back.
        handleView = new TextView(this);
        handleView.setTextColor(0xFFFFFFFF);
        handleView.setTextSize(13f);
        handleView.setTypeface(Typeface.DEFAULT_BOLD);
        handleView.setGravity(Gravity.CENTER);
        handleView.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        handleView.setVisibility(View.GONE);
        root.addView(handleView);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE keeps keyboard/input focus in the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,   // tap anywhere else -> hide
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", dp(12));
        lp.y = prefs.getInt("y", dm.heightPixels / 4);
        wm.addView(root, lp);
        root.post(root::clampAndApply);
    }

    /** Hide = shrink to a small round handle; show = full 4-button panel. */
    private void setCollapsed(boolean c) {
        if (root == null || collapsed == c) return;
        collapsed = c;
        counterBox.setVisibility(c ? View.GONE : View.VISIBLE);
        gridBox.setVisibility(c ? View.GONE : View.VISIBLE);
        handleView.setVisibility(c ? View.VISIBLE : View.GONE);
        rootBg.setCornerRadius(dp(c ? 24 : 20));
        if (c) root.setPadding(dp(4), dp(4), dp(4), dp(4));
        else root.setPadding(dp(8), dp(6), dp(6), dp(6));
        root.post(root::clampAndApply);
    }

    /** Pill-shaped container that can be dragged from anywhere without breaking button taps. */
    private class DragLayout extends LinearLayout {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        DragLayout(Context c) { super(c); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); startX = lp.x; startY = lp.y; dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop)) {
                        dragging = true;
                        return true;
                    }
                    break;
                default: break;
            }
            return dragging;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_OUTSIDE:           // touch landed anywhere outside the tool
                    setCollapsed(true);
                    return true;
                case MotionEvent.ACTION_DOWN: return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop)) dragging = true;
                    if (dragging) {
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        clampAndApply();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    else if (collapsed && e.getActionMasked() == MotionEvent.ACTION_UP) setCollapsed(false);
                    dragging = false;
                    return true;
                default: return true;
            }
        }

        void clampAndApply() {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - Math.max(getWidth(), 1)));
            lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - Math.max(getHeight(), 1)));
            if (isAttachedToWindow()) wm.updateViewLayout(this, lp);
        }
    }
}
