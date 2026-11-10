package com.emailpool.manager;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process pub/sub so the WebView UI and the overlay stay in sync. */
final class QueueBus {
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void register(Runnable r) { if (!LISTENERS.contains(r)) LISTENERS.add(r); }
    static void unregister(Runnable r) { LISTENERS.remove(r); }

    static void changed() {
        MAIN.post(() -> { for (Runnable r : LISTENERS) r.run(); });
    }

    private QueueBus() { }
}
