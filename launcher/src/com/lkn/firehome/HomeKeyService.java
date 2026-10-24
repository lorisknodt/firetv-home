package com.lkn.firehome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS keeps the Home button for the Amazon launcher no matter which app is the default home, and
 * doesn't let accessibility services filter that key either. So this service follows the system log
 * (READ_LOGS, granted over ADB) and replaces the Amazon home as soon as the system starts it, and also
 * opens SmartTube when the remote's YouTube button is refused. Window events from the Amazon launcher
 * are a fallback for the cases with no log line, like Back out of Amazon settings.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String AMAZON_HOME_START = "cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext";
    // The remote's YouTube button only launches Amazon-signed apps; for anything else Fire OS logs
    // this line and gives up. No window or key reaches us, so the log line is the only signal.
    private static final String YOUTUBE_REFUSED = "App youtube is not trusted";
    // Settings and the Appstore open by passing through the Amazon home; the log shows the app asked.
    private static final long APP_HOME_GRACE_MS = 2500;
    private static final long FALLBACK_MS = 1000;

    private static HomeKeyService running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread watcher;
    private volatile Process logcat;
    private volatile long appAskedHomeAt = -APP_HOME_GRACE_MS;
    private volatile long handledAt = -APP_HOME_GRACE_MS; // the log already replaced the Amazon home
    private long otherLauncherScreenAt = -APP_HOME_GRACE_MS;
    private final Runnable replaceAmazonHome = () -> {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) { }
    };

    /** Puts the TV to sleep; only an accessibility service may do this without system rights. */
    static void sleep() {
        if (running != null) running.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        // The system can connect the same service twice; one log reader is enough.
        if (watcher != null && watcher.isAlive()) return;
        watcher = new Thread(this::watchSystemLog, "system-log");
        watcher.start();
    }

    @Override
    public boolean onUnbind(Intent i) {
        running = null;
        if (watcher != null) watcher.interrupt();
        Process p = logcat;
        if (p != null) p.destroy();
        return super.onUnbind(i);
    }

    // ---------------------------------------------------------------- system log

    /** logd filters by tag, so this sleeps until an activity start or an am_wtf line. */
    private void watchSystemLog() {
        String youtube = getString(R.string.youtube_button_package);
        while (running == this && !Thread.currentThread().isInterrupted()) {
            // Without READ_LOGS logcat only shows our own lines, so wait for install.sh to grant it.
            if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            long now = System.currentTimeMillis();
            String since = (now / 1000) + "." + String.format(java.util.Locale.ROOT, "%03d", now % 1000);
            try {
                logcat = new ProcessBuilder("logcat", "-b", "events,system", "-v", "epoch", "-T", since,
                        "-s", "am_wtf:I", "ActivityTaskManager:I").redirectErrorStream(true).start();
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(logcat.getInputStream()))) {
                    for (String line; (line = in.readLine()) != null; ) onLogLine(line, youtube);
                }
                Thread.sleep(5000); // logcat exited (e.g. permission missing): don't spin
            } catch (java.io.IOException | InterruptedException e) {
                return;
            }
        }
    }

    private void onLogLine(String line, String youtube) {
        if (line.contains(YOUTUBE_REFUSED)) {
            handler.post(() -> openPackage(youtube));
        } else if (line.contains("START u0") && line.contains(AMAZON_HOME_START)) {
            // Home key and boot come from the system (uid 0 or 1000); anything else is an app
            // (Amazon settings, the Appstore) that needs the Amazon home underneath it.
            if (line.endsWith("from uid 0") || line.endsWith("from uid 1000")) {
                handledAt = SystemClock.uptimeMillis();
                handler.post(() -> {
                    handler.removeCallbacks(replaceAmazonHome);
                    replaceAmazonHome.run();
                });
            } else {
                appAskedHomeAt = SystemClock.uptimeMillis();
            }
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    // ---------------------------------------------------------------- fallback

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long now = SystemClock.uptimeMillis();
        if (!AMAZON_HOME.contentEquals(e.getClassName() == null ? "" : e.getClassName())) {
            // Another Amazon launcher screen (settings) is opening over the home: leave it be.
            handler.removeCallbacks(replaceAmazonHome);
            otherLauncherScreenAt = now;
        } else if (now - appAskedHomeAt >= APP_HOME_GRACE_MS && now - otherLauncherScreenAt >= APP_HOME_GRACE_MS
                && now - handledAt >= APP_HOME_GRACE_MS && !handler.hasCallbacks(replaceAmazonHome)) {
            handler.postDelayed(replaceAmazonHome, FALLBACK_MS);
        }
    }

    @Override
    public void onInterrupt() { }
}
