package kz.kitdev.chat;

import android.content.Context;

import java.util.Locale;

public class LangManager {

    private static final String PREF_NAME = "vd_lang";
    private static final String KEY_LANG = "lang";

    public static String get(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANG, "ru");
    }

    public static void set(Context context, String lang) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANG, lang).apply();
    }

    /**
     * Возвращает Locale с регионом для TTS и SpeechRecognizer.
     * Регион важен: kk-KZ даёт лучшее распознавание, чем просто "kk".
     */
    public static Locale toLocale(String lang) {
        switch (lang) {
            case "kk": return new Locale("kk", "KZ");
            case "en": return Locale.US;
            default:   return new Locale("ru", "RU");
        }
    }

    /**
     * BCP-47 тег языка для SpeechRecognizer (например "kk-KZ", "ru-RU", "en-US").
     * Используется в EXTRA_LANGUAGE вместо Locale.toString().
     */
    public static String toLanguageTag(String lang) {
        return toLocale(lang).toLanguageTag();
    }
}
