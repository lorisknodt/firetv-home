package com.lkn.firehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

/**
 * Picks wallpapers by time of day: assets/wallpapers/jour during the day, assets/wallpapers/soir in
 * the evening and at night. Hours are in config.xml. An app can have its own set in
 * assets/wallpapers/apps/{package}, shown while it is focused on the home screen.
 */
final class Wallpapers {

    static final String DAY = "jour", EVENING = "soir";

    private Wallpapers() { }

    static String period(Context c) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= c.getResources().getInteger(R.integer.day_from) && h < c.getResources().getInteger(R.integer.day_until)
                ? DAY : EVENING;
    }

    /** Late at night the photo is dimmed so the screen doesn't light up the room. */
    static boolean isNight(Context c) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= c.getResources().getInteger(R.integer.night_from) || h < c.getResources().getInteger(R.integer.night_until);
    }

    /**
     * Asset path of the next photo in wallpapers/{folder} (a period, or apps/{package}), cycling
     * through it, or the one picked for that folder; null if the folder is empty or missing.
     */
    static String next(Context c, String folder) {
        String[] files = list(c, folder);
        if (files.length == 0) return null;
        String picked = picked(c, folder);
        if (picked != null && java.util.Arrays.asList(files).contains(picked)) return path(folder, picked);
        SharedPreferences p = prefs(c);
        int i = (p.getInt(folder, -1) + 1) % files.length;
        p.edit().putInt(folder, i).apply();
        return path(folder, files[i]);
    }

    /** The photo chosen in the picker for a folder, or null when the folder rotates. */
    static String picked(Context c, String folder) {
        return prefs(c).getString("picked/" + folder, null);
    }

    /** Keeps one photo for a folder; null goes back to rotating through all of them. */
    static void pick(Context c, String folder, String file) {
        if (file == null) prefs(c).edit().remove("picked/" + folder).apply();
        else prefs(c).edit().putString("picked/" + folder, file).apply();
    }

    static String path(String folder, String file) {
        return "wallpapers/" + folder + "/" + file;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
    }

    /** Folder of an app's own photos (assets/wallpapers/apps/{package}), or null if it has none. */
    static String appFolder(Context c, String pkg) {
        String folder = "apps/" + pkg;
        return list(c, folder).length > 0 ? folder : null;
    }

    static String[] list(Context c, String folder) {
        try {
            String[] files = c.getAssets().list("wallpapers/" + folder);
            if (files == null) return new String[0];
            // Only images; a stray note or subfolder would otherwise show as a blank wallpaper.
            return java.util.Arrays.stream(files)
                    .filter(f -> f.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp)"))
                    .toArray(String[]::new);
        } catch (IOException e) {
            return new String[0];
        }
    }

    /** Full-screen photo. */
    static Bitmap decode(Context c, String path) {
        return decode(c, path, c.getResources().getDisplayMetrics().widthPixels);
    }

    /**
     * Decodes in RGB_565 (half the memory of ARGB, and a photo has no transparency), halving the
     * size while it stays at least {@code width} wide: a 4K photo takes 4 MB instead of 16.
     */
    static Bitmap decode(Context c, String path, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getAssets().open(path)) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }
}
