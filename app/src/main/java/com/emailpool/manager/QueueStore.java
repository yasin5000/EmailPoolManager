package com.emailpool.manager;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

/** Persistent FIFO email queue backed by SQLite. Shared by the UI and the overlay service. */
public final class QueueStore extends SQLiteOpenHelper {
    private static QueueStore instance;
    /** Last email handed to the clipboard (used as a fallback by PASTE). */
    public static volatile String lastCopied;

    public static synchronized QueueStore get(Context c) {
        if (instance == null) instance = new QueueStore(c.getApplicationContext());
        return instance;
    }

    private QueueStore(Context c) { super(c, "pool.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE emails(id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL UNIQUE)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    /** Inserts emails (duplicates ignored). Returns number actually added. */
    public synchronized int addAll(List<String> emails) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            SQLiteStatement st = db.compileStatement("INSERT OR IGNORE INTO emails(email) VALUES(?)");
            for (String e : emails) {
                st.clearBindings();
                st.bindString(1, e);
                if (st.executeInsert() != -1) added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    public synchronized int count() {
        return (int) DatabaseUtils.queryNumEntries(getReadableDatabase(), "emails");
    }

    /** Atomically takes the oldest email out of the queue. Null if empty. */
    public synchronized String popNext() {
        SQLiteDatabase db = getWritableDatabase();
        String result = null;
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT id, email FROM emails ORDER BY id LIMIT 1", null)) {
                if (c.moveToFirst()) {
                    result = c.getString(1);
                    db.delete("emails", "id=?", new String[]{String.valueOf(c.getLong(0))});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    public synchronized List<String> list(int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT email FROM emails ORDER BY id LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public synchronized void remove(String email) {
        getWritableDatabase().delete("emails", "email=?", new String[]{email});
    }

    public synchronized void clear() { getWritableDatabase().delete("emails", null, null); }
}
