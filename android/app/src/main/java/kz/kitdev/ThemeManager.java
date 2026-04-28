package kz.kitdev;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

public class ThemeManager {

    private static final String PREFS = "vd_theme";
    private static final String KEY_DARK = "dark_mode";

    public static boolean isDark(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK, false);
    }

    public static void toggle(Context context) {
        boolean nowDark = !isDark(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DARK, nowDark).apply();
        apply(nowDark);
    }

    public static void apply(boolean dark) {
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    public static void init(Context context) {
        apply(isDark(context));
    }
}
