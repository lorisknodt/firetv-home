package com.lkn.firehome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Keeps the encrypted Private DNS (setup.sh sets AdGuard) from locking the TV offline after a restart.
 * A Fire TV stick has no clock battery: it boots on an old date and asks the internet for the time.
 * Encrypted DNS refuses certificates "from the future" on that old date, so no name resolves, the
 * time server can't be reached and the date never gets fixed. When the clock is behind, this switches
 * Private DNS to automatic until the time has synced, then puts the strict server back.
 * Needs WRITE_SECURE_SETTINGS, granted over ADB by setup.sh.
 */
final class ClockGuard {

    private static final String MODE = "private_dns_mode", STRICT = "hostname", AUTOMATIC = "opportunistic";
    private static final long RECHECK_MS = 10_000;

    private ClockGuard() { }

    static void start(Context context) {
        Context c = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override public void run() {
                if (!check(c)) handler.postDelayed(this, RECHECK_MS);
            }
        });
    }

    /** False while the clock is behind the last time it was seen right (just after a restart). */
    static boolean clockRight(Context c) {
        SharedPreferences p = c.getSharedPreferences("clock", Context.MODE_PRIVATE);
        return System.currentTimeMillis() >= Math.max(p.getLong("lastGood", 0), installTime(c));
    }

    /** True once there is nothing left to do. */
    private static boolean check(Context c) {
        SharedPreferences p = c.getSharedPreferences("clock", Context.MODE_PRIVATE);
        ContentResolver cr = c.getContentResolver();
        long now = System.currentTimeMillis();
        // The clock can't be earlier than the last time it was seen right, nor than our install.
        long known = Math.max(p.getLong("lastGood", 0), installTime(c));
        boolean paused = p.getBoolean("paused", false);
        try {
            if (now < known) {
                if (STRICT.equals(Settings.Global.getString(cr, MODE))) {
                    Settings.Global.putString(cr, MODE, AUTOMATIC);
                    p.edit().putBoolean("paused", true).apply();
                    paused = true;
                }
                return !paused; // keep watching only while we hold the DNS on automatic
            }
            if (paused) Settings.Global.putString(cr, MODE, STRICT);
            p.edit().putLong("lastGood", now).putBoolean("paused", false).apply();
        } catch (SecurityException e) {
            android.util.Log.w("firehome", "WRITE_SECURE_SETTINGS missing: run setup.sh again", e);
        }
        return true;
    }

    private static long installTime(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
}
