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
 * the evening and at night. Hours are in config.xml.
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

    /** Asset path of the next photo for the period, cycling through its folder; null if it is empty. */
    static String next(Context c, String period) {
        String[] files;
        try {
            files = c.getAssets().list("wallpapers/" + period);
        } catch (IOException e) {
            return null;
        }
        if (files == null || files.length == 0) return null;
        SharedPreferences p = c.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        int i = (p.getInt(period, -1) + 1) % files.length;
        p.edit().putInt(period, i).apply();
        return "wallpapers/" + period + "/" + files[i];
    }

    /** Decodes in RGB_565: half the memory of ARGB, and a photo has no transparency. */
    static Bitmap decode(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }
}
