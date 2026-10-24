package com.lkn.firehome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, buttons top-right, the focused dock app's name, a
 * dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no background work; the only network use is Weather.
 * Fire TV fork: French UI, quick settings panel instead of TV inputs, Home button via HomeKeyService.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private ImageView wallBack, wallFront;
    private View gridShade, nightShade;
    private String shownPeriod;
    // App whose own photos are showing (assets/wallpapers/apps/{package}), or null for the day/evening set.
    private String wallApp, pendingWallApp;
    private int wallSeq;
    private boolean quickFade;
    private final Runnable switchWallApp = () -> {
        wallApp = pendingWallApp;
        quickFade = true;
        nextWallpaper();
    };
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, stats, title, appsLabel;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow panelPopup, pickerPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateStats(); followTimeOfDay(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateStats();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
        followTimeOfDay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (panelPopup != null) panelPopup.dismiss();
        if (pickerPopup != null) pickerPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (panelPopup != null) panelPopup.dismiss();
        if (pickerPopup != null) pickerPopup.dismiss();
        // onResume runs after this and restores lastFocusedId, so point it at the dock too.
        lastFocusedId = dockCards.isEmpty() ? View.NO_ID : dockCards.get(0).getId();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        nightShade = new View(this);
        nightShade.setBackgroundColor(0x73000000);
        nightShade.setAlpha(0f);
        root.addView(nightShade, new FrameLayout.LayoutParams(-1, -1));


        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("TOUTES LES APPLIS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(12, "sans-serif-medium", 0xE6FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(62, "sans-serif", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(13, "sans-serif-medium", 0xE6FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);

        // Weather · RAM, one line under the date (the display mode is in the settings panel).
        stats = text(14, "sans-serif", 0xF2FFFFFF);
        stats.setSingleLine(true);
        left.addView(stats, llp(-2, -2, 0, dp(16), 0, 0));
        // No shadow on the stats line: on Fire OS it draws as a grey box behind the text.
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(600), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        View wifi = iconButton(R.drawable.ic_wifi);
        wifi.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View photo = iconButton(R.drawable.ic_image);
        photo.setOnClickListener(v -> showWallpaperPicker());
        View settings = iconButton(R.drawable.ic_settings);
        settings.setOnClickListener(v -> showPanel());
        for (View p : new View[]{wifi, photo, settings}) {
            right.addView(p, llp(dp(34), dp(34), dp(12), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    /** Round frosted button with a white icon; turns white with a dark icon when focused. */
    private View iconButton(int icon) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setFocusable(true);
        b.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x40FFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            b.setColorFilter(has ? 0xFF111111 : 0xFFFFFFFF);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); shadeGrid(false); }
        });
        return b;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        // Removing the focused tile hands focus to a pill, whose listener overwrites lastFocusedId,
        // so remember what was focused first: a tile by its app, a pill by its view.
        View prev = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        String prevPkg = prev != null && prev.getTag() instanceof App ? ((App) prev.getTag()).pkg : null;
        View prevPill = pills.contains(prev) ? prev : null;

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) title.setText(dock.get(0).label);
        View last = prevPill;
        if (prevPkg != null) for (View c : concat(dockCards, gridCards)) {
            if (prevPkg.equals(((App) c.getTag()).pkg)) last = c;
        }
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) { last.requestFocus(); lastFocusedId = last.getId(); }
    }

    private static List<View> concat(List<View> a, List<View> b) {
        List<View> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            focusWallpaper(app.pkg);
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                shadeGrid(false);
                scroll.smoothScrollTo(0, 0);
            } else {
                shadeGrid(true);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- quick settings

    private static final String FIRE_SETTINGS = "com.amazon.tv.settings.v2";

    /** Dropdown under the Réglages pill: shortcuts into Fire OS settings, plus sleep. */
    private void showPanel() {
        if (panelPopup != null || pills.isEmpty()) return;
        View anchor = pills.get(pills.size() - 1);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xF0242428);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(240), -2, true);
        List<View> rows = new ArrayList<>();
        rows.add(panelRow("Wi-Fi", wifiName(), () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        rows.add(panelRow("Bluetooth et manettes", "", () -> {
            if (!launch(new Intent().setClassName(FIRE_SETTINGS,
                    FIRE_SETTINGS + ".tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"))) {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        }));
        // Fire OS won't let other apps open Display & sounds itself, but its soundbar page is allowed.
        rows.add(panelRow("Barre de son", "", () -> {
            if (!launch(new Intent("com.amazon.device.settings.action.SOUNDBAR_SETTINGS"))) {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        }));
        // The Appstore is a tab of the Amazon home on Fire OS 8; HomeKeyService leaves it alone.
        if (isInstalled("com.amazon.venezia")) rows.add(panelRow("Appstore", "", () -> openPackage("com.amazon.venezia")));
        int firstAction = rows.size();
        rows.add(panelRow("Mettre en veille", "", HomeKeyService::sleep));
        rows.add(panelRow("Tous les réglages", displayMode(), () -> launch(new Intent(Settings.ACTION_SETTINGS))));

        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            if (i == firstAction) {
                View sep = new View(this);
                sep.setBackgroundColor(0x1AFFFFFF);
                list.addView(sep, llp(-1, dp(1), dp(12), dp(4), dp(12), dp(4)));
            }
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(38)));
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { panelPopup = null; anchor.requestFocus(); });
        panelPopup = pw;
        scroll.smoothScrollTo(0, 0);
        pw.showAsDropDown(anchor, anchor.getWidth() - dp(240), dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private View panelRow(String label, String value, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        TextView name = text(13, "sans-serif", Color.WHITE);
        name.setText(label);
        name.setSingleLine(true);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = text(11, "sans-serif", 0x8CFFFFFF);
        val.setText(value);
        val.setSingleLine(true);
        r.addView(val);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            name.setTextColor(has ? 0xFF111111 : Color.WHITE);
            val.setTextColor(has ? 0x99111111 : 0x8CFFFFFF);
        });
        return r;
    }

    private String displayMode() {
        Display.Mode m = getWindowManager().getDefaultDisplay().getMode();
        int h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
        String res = h >= 2160 ? "4K" : h >= 1080 ? "1080p" : h + "p";
        return res + " · " + Math.round(m.getRefreshRate()) + " Hz";
    }

    // ---------------------------------------------------------------- wallpaper picker

    private static final int THUMB_DP = 176;

    /**
     * Thumbnails of the current set (the last focused app's own photos, else day/evening).
     * "Toutes" keeps rotating through them; a thumbnail keeps that one photo for the set.
     */
    private void showWallpaperPicker() {
        if (pickerPopup != null) return;
        String folder = wallApp != null ? wallApp : Wallpapers.period(this);
        String[] files = Wallpapers.list(this, folder);
        if (files.length == 0) return;
        String picked = Wallpapers.picked(this, folder);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(48), dp(22), dp(48), dp(30));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6101014, 0xF5060608});
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        panel.setBackground(bg);

        TextView heading = text(12, "sans-serif-medium", 0xCCFFFFFF);
        heading.setLetterSpacing(0.2f);
        heading.setText(("Fond d'écran  ·  " + setLabel(folder)).toUpperCase(Locale.FRANCE));
        panel.addView(heading, llp(-2, -2, dp(6), 0, 0, dp(16)));

        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        strip.addView(row);
        panel.addView(strip, new LinearLayout.LayoutParams(-1, -2));

        PopupWindow pw = new PopupWindow(panel, -1, -2, true);
        List<View> tiles = new ArrayList<>();
        View start = null;
        tiles.add(pickerTile(folder, null, picked == null, pw));
        if (picked == null) start = tiles.get(0);
        for (String f : files) {
            View t = pickerTile(folder, f, f.equals(picked), pw);
            tiles.add(t);
            if (f.equals(picked)) start = t;
        }
        for (View t : tiles) {
            t.setNextFocusUpId(t.getId());
            t.setNextFocusDownId(t.getId());
            row.addView(t, llp(dp(THUMB_DP), dp(THUMB_DP) * 9 / 16, 0, 0, dp(18), 0));
        }
        tiles.get(0).setNextFocusLeftId(tiles.get(0).getId());
        View last = tiles.get(tiles.size() - 1);
        last.setNextFocusRightId(last.getId());

        pw.setOnDismissListener(() -> {
            pickerPopup = null;
            if (pills.size() > 1) pills.get(1).requestFocus();
        });
        pickerPopup = pw;
        pw.showAtLocation(getWindow().getDecorView(), Gravity.BOTTOM, 0, 0);
        (start != null ? start : tiles.get(0)).requestFocus();

        // Thumbnails decode one after the other, small, off the main thread.
        new Thread(() -> {
            for (int i = 0; i < files.length; i++) {
                Bitmap bm = Wallpapers.decode(this, Wallpapers.path(folder, files[i]), dp(THUMB_DP) * 2);
                ImageView img = (ImageView) ((FrameLayout) tiles.get(i + 1)).getChildAt(0);
                runOnUiThread(() -> img.setImageBitmap(bm));
            }
        }, "thumbnails").start();
    }

    /** One 16:9 thumbnail; file null is the "Toutes" tile that keeps the set rotating. */
    private View pickerTile(String folder, String file, boolean current, PopupWindow pw) {
        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C20);
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (file == null) {
            TextView all = text(13, "sans-serif-medium", Color.WHITE);
            all.setText("⟳\nToutes");
            all.setGravity(Gravity.CENTER);
            card.addView(all, new FrameLayout.LayoutParams(-1, -1));
        }
        if (current) {
            TextView check = text(12, "sans-serif-medium", 0xFF111111);
            check.setText("✓");
            check.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xF2FFFFFF);
            check.setBackground(dot);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(6), dp(6), 0);
            card.addView(check, lp);
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(10) : 0)
                    .setDuration(200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(150).start();
        });
        card.setOnClickListener(v -> {
            Wallpapers.pick(this, folder, file);
            pw.dismiss();
            quickFade = true;
            nextWallpaper();
        });
        return card;
    }

    private String setLabel(String folder) {
        if (Wallpapers.DAY.equals(folder)) return "Jour";
        if (Wallpapers.EVENING.equals(folder)) return "Soir";
        String pkg = folder.substring(folder.indexOf('/') + 1);
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 12 ? "BONJOUR" : h >= 12 && h < 18 ? "BON APRÈS-MIDI" : "BONSOIR");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }


    /** Runs every minute while home is showing; the weather fetch only fires every 30 minutes. */
    private void updateStats() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        float gb = 1024f * 1024f * 1024f;
        String ram = String.format(Locale.FRANCE, "RAM %.1f / %.1f Go", (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb);
        String weather = Weather.cached(this);
        stats.setText((weather != null ? weather + "   ·   " : "") + ram);
        Weather.refreshIfStale(this, w -> updateStats());
    }

    private String wifiName() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Hors ligne";
            }
        } catch (SecurityException ignored) { }
        return name;
    }

    /** The grid sits over the photo's busiest middle part, so dim the photo while browsing apps. */
    private void shadeGrid(boolean on) {
        gridShade.animate().alpha(on ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /**
     * Decode the next photo (the focused app's own set, else the current period's) off the main
     * thread, then crossfade it in. A newer call wins if two decodes finish out of order.
     */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        shownPeriod = Wallpapers.period(this);
        String name = Wallpapers.next(this, wallApp != null ? wallApp : shownPeriod);
        if (name == null) return;
        wallIndex = 0;
        wallChangedAt = SystemClock.elapsedRealtime();
        int seq = ++wallSeq;
        new Thread(() -> {
            Bitmap result = Wallpapers.decode(this, name);
            runOnUiThread(() -> { if (seq == wallSeq) showWallpaper(result); });
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    /** Switches to the app's own photos (or back to the default set) once focus rests on it. */
    private void focusWallpaper(String pkg) {
        String folder = Wallpapers.appFolder(this, pkg);
        handler.removeCallbacks(switchWallApp);
        if (java.util.Objects.equals(folder, wallApp)) return;
        pendingWallApp = folder;
        handler.postDelayed(switchWallApp, 350);
    }

    /** Called every minute: switch to the evening photos at the set hour, dim late at night. */
    private void followTimeOfDay() {
        if (wallApp == null && shownPeriod != null && !shownPeriod.equals(Wallpapers.period(this))) nextWallpaper();
        float night = Wallpapers.isNight(this) ? 1f : 0f;
        if (nightShade.getAlpha() != night) nightShade.animate().alpha(night).setDuration(2000).start();
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : quickFade ? 450 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
        quickFade = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation: the backdrop of icon-only tiles. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on the remote opens the quick settings.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            showPanel();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
