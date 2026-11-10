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

/** Foreground service that owns the draggable always-on-top COPY / PASTE overlay. */
public class OverlayService extends Service {
    static final String ACTION_STOP = "com.emailpool.manager.STOP";
    static volatile boolean running;

    private static final String CHANNEL = "overlay";
    private static final int NOTIF_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onQueueChanged = this::refresh;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private DragLayout root;
    private TextView countView;
    private Button copyBtn, pasteBtn;
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
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root == null) createOverlay();
        QueueBus.register(onQueueChanged);
        running = true;
        refresh();
        return START_STICKY;   // system restarts the tool if the process is killed
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (root != null) root.post(() -> { root.clampAndApply(); });
    }

    @Override public void onDestroy() {
        running = false;
        QueueBus.unregister(onQueueChanged);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notification

    private Notification buildNotification(int count) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Email Pool is active")
                .setContentText(count + " email" + (count == 1 ? "" : "s") + " remaining")
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
            countView.setText(n >= 10000 ? (n / 1000) + "k" : String.valueOf(n));
            copyBtn.setAlpha(n > 0 ? 1f : 0.45f);
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(n));
    }

    // ---------------------------------------------------------------- actions

    private void doCopy() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTap < 300) return;       // ignore accidental double taps
        lastTap = now;
        String email = QueueStore.get(this).popNext();
        if (email == null) { Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show(); refresh(); return; }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("email", email));
        QueueStore.lastCopied = email;
        flash(copyBtn, "COPIED", "COPY");
        QueueBus.changed();
    }

    private void doPaste() {
        EmailAccessibilityService s = EmailAccessibilityService.instance;
        if (s == null) {
            Toast.makeText(this, "Turn on Email Pool in Accessibility settings to enable PASTE", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
            return;
        }
        switch (s.pasteIntoFocused(QueueStore.lastCopied)) {
            case EmailAccessibilityService.OK: flash(pasteBtn, "PASTED", "PASTE"); break;
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
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c1, c2});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button makeButton(String label, int c1, int c2, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.05f);
        b.setStateListAnimator(null);
        b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), grad(c1, c2, 18), null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(72), dp(36));
        p.setMargins(dp(4), 0, dp(4), 0);
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
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), 0x33FFFFFF);
        root.setBackground(bg);
        root.setElevation(dp(8));

        LinearLayout counter = new LinearLayout(this);       // drag handle + remaining count
        counter.setOrientation(LinearLayout.VERTICAL);
        counter.setGravity(Gravity.CENTER);
        counter.setMinimumWidth(dp(38));
        countView = new TextView(this);
        countView.setTextColor(0xFFFFFFFF);
        countView.setTextSize(15f);
        countView.setTypeface(Typeface.DEFAULT_BOLD);
        countView.setGravity(Gravity.CENTER);
        TextView cap = new TextView(this);
        cap.setText("LEFT");
        cap.setTextColor(0x99FFFFFF);
        cap.setTextSize(8f);
        cap.setLetterSpacing(0.1f);
        cap.setGravity(Gravity.CENTER);
        counter.addView(countView);
        counter.addView(cap);
        root.addView(counter);

        copyBtn = makeButton("COPY", 0xFF6366F1, 0xFF8B5CF6, v -> doCopy());
        pasteBtn = makeButton("PASTE", 0xFF0EA5A4, 0xFF22C55E, v -> doPaste());
        root.addView(copyBtn);
        root.addView(pasteBtn);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE keeps keyboard/input focus in the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", dp(12));
        lp.y = prefs.getInt("y", dm.heightPixels / 3);
        wm.addView(root, lp);
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
