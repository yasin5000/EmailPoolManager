package com.emailpool.manager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/** Talks to the Google Apps Script license server (see server/Code.gs). */
final class LicenseServer {
    /**
     * Paste your Apps Script "Web app" URL between the quotes (it ends with /exec).
     * Left empty, the app works without a server and a key is NOT tied to one phone.
     */
    static final String URL = "https://script.google.com/macros/s/AKfycbwx1qrqIqF93zi5EVyrxpNK1LP5mcFKi5hhWXL8mNngICVWfImU86ywChINttB0VnXj/exec";

    static boolean configured() { return URL.startsWith("https://"); }

    /** Blocking GET -> JSON. Call from a background thread only. */
    static JSONObject get(Map<String, String> params) throws IOException {
        StringBuilder q = new StringBuilder(URL).append(URL.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) q.append('&');
            first = false;
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(q.toString()).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);          // Apps Script answers through a redirect
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            try {
                return new JSONObject(out.toString("UTF-8"));
            } catch (Exception e) {
                throw new IOException("Bad reply from server");
            }
        } finally {
            c.disconnect();
        }
    }

    private LicenseServer() { }
}
