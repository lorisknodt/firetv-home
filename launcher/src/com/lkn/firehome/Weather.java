package com.lkn.firehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current weather for the status line. The Fire TV has no GPS, so the city comes from the public IP
 * (ipapi.co, cached a day) and the weather from Open-Meteo (no key, refreshed every 30 minutes and
 * only while the home screen asks for it). Everything runs on one short-lived background thread.
 */
final class Weather {

    interface Listener { void onWeather(String text); }

    private static final long CITY_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final long WEATHER_TTL_MS = 30 * 60 * 1000L;
    private static final long SHOW_FOR_MS = 3 * 60 * 60 * 1000L; // hide a stale reading after 3 h
    private static boolean fetching;

    private Weather() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** Last reading, or null if there is none recent enough to show. */
    static String cached(Context c) {
        SharedPreferences p = prefs(c);
        if (System.currentTimeMillis() - p.getLong("weatherAt", 0) > SHOW_FOR_MS) return null;
        return p.getString("text", null);
    }

    /** Fetches in the background if the reading is older than 30 minutes; calls back on the main thread. */
    static void refreshIfStale(Context context, Listener listener) {
        Context c = context.getApplicationContext();
        SharedPreferences p = prefs(c);
        if (fetching || System.currentTimeMillis() - p.getLong("weatherAt", 0) < WEATHER_TTL_MS) return;
        fetching = true;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String text = null;
            try {
                text = fetch(p);
            } catch (Exception ignored) {
                // Offline or a service is down: keep the last reading.
            }
            String result = text;
            main.post(() -> {
                fetching = false;
                if (result != null) listener.onWeather(result);
            });
        }, "weather").start();
    }

    private static String fetch(SharedPreferences p) throws Exception {
        long now = System.currentTimeMillis();
        if (now - p.getLong("cityAt", 0) > CITY_TTL_MS) {
            JSONObject ip = new JSONObject(get("https://ipapi.co/json/"));
            p.edit().putString("city", ip.optString("city", ""))
                    .putFloat("lat", (float) ip.getDouble("latitude"))
                    .putFloat("lon", (float) ip.getDouble("longitude"))
                    .putLong("cityAt", now).apply();
        }
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code",
                p.getFloat("lat", 0), p.getFloat("lon", 0));
        JSONObject current = new JSONObject(get(url)).getJSONObject("current");
        String text = symbol(current.getInt("weather_code")) + " " + Math.round(current.getDouble("temperature_2m"))
                + "°  " + p.getString("city", "");
        p.edit().putString("text", text.trim()).putLong("weatherAt", now).apply();
        return text.trim();
    }

    /** WMO weather code to a plain symbol. */
    private static String symbol(int code) {
        if (code == 0) return "☀";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "≋";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 95) return "ϟ";
        return "☂"; // drizzle, rain, showers
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "firehome/1.0");
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
