package kz.kitdev.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

public class PersistentCookieJar implements CookieJar {

    private static final String PREF_NAME = "vd_cookies";
    private final SharedPreferences prefs;

    public PersistentCookieJar(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        Set<String> serialized = new HashSet<>(prefs.getStringSet(url.host(), new HashSet<>()));
        for (Cookie cookie : cookies) {
            // Удаляем старое значение той же куки перед сохранением
            serialized.removeIf(s -> s.startsWith(cookie.name() + "="));
            if (cookie.expiresAt() > System.currentTimeMillis()) {
                serialized.add(cookie.name() + "=" + cookie.value());
            }
        }
        prefs.edit().putStringSet(url.host(), serialized).apply();
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        Set<String> serialized = prefs.getStringSet(url.host(), new HashSet<>());
        List<Cookie> cookies = new ArrayList<>();
        for (String s : serialized) {
            int idx = s.indexOf('=');
            if (idx > 0) {
                String name = s.substring(0, idx);
                String value = s.substring(idx + 1);
                cookies.add(new Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host())
                        .build());
            }
        }
        return cookies;
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
