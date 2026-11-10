package com.emailpool.manager;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

/** Persistent FIFO queue of email|password pairs, backed by SQLite. */
public final class QueueStore extends SQLiteOpenHelper {
    private static QueueStore instance;
    /** Last values handed out, used so PASTE always inserts the right field even if the
     *  clipboard has since been overwritten by the other field. */
    public static volatile String lastEmail;
    public static volatile String lastPassword;

    public static synchronized QueueStore get(Context c) {
        if (instance == null) instance = new QueueStore(c.getApplicationContext());
        return instance;
    }

    private QueueStore(Context c) { super(c, "pool.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE emails(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "email TEXT NOT NULL UNIQUE, password TEXT NOT NULL DEFAULT '')");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            try { db.execSQL("ALTER TABLE emails ADD COLUMN password TEXT NOT NULL DEFAULT ''"); }
            catch (Exception ignored) { }
        }
    }

    /** A single email/password record. */
    public static final class Pair {
        public final String email, password;
        public Pair(String e, String p) { email = e; password = p; }
    }

    /** Inserts pairs (duplicate emails ignored). Returns number actually added. */
    public synchronized int addAll(List<Pair> pairs) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            SQLiteStatement st = db.compileStatement("INSERT OR IGNORE INTO emails(email,password) VALUES(?,?)");
            for (Pair p : pairs) {
                st.clearBindings();
                st.bindString(1, p.email);
                st.bindString(2, p.password == null ? "" : p.password);
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

    /** Looks at the oldest pair without removing it. Null if the queue is empty. */
    public synchronized Pair peek() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT email, password FROM emails ORDER BY id LIMIT 1", null)) {
            if (c.moveToFirst()) return new Pair(c.getString(0), c.getString(1));
        }
        return null;
    }

    public synchronized List<Pair> list(int limit) {
        List<Pair> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT email, password FROM emails ORDER BY id LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) out.add(new Pair(c.getString(0), c.getString(1)));
        }
        return out;
    }

    public synchronized void remove(String email) {
        getWritableDatabase().delete("emails", "email=?", new String[]{email});
    }

    public synchronized void clear() { getWritableDatabase().delete("emails", null, null); }
}
