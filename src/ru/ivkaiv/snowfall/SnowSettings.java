package ru.ivkaiv.snowfall;

import android.content.Context;
import android.content.SharedPreferences;

final class SnowSettings {
    static final String FILE = "snow_settings";
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    static boolean economy(SharedPreferences preferences) {
        return preferences.getBoolean("eco", false);
    }
    static void apply(Context context, SnowView view) {
        SharedPreferences p = prefs(context);
        view.configure(p.getInt("intensity", 42), p.getInt("drift", 48),
                p.getBoolean("tree", true), p.getBoolean("left", false),
                economy(p));
        view.setCleanupSpeed(p.getInt("cleanup_speed", 45));
    }
    private SnowSettings() { }
}
