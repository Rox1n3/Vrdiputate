package kz.kitdev.session;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF_NAME = "vd_user";
    private static final String KEY_ID = "id";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_NAME = "name";

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveUser(int id, String email, String name) {
        prefs.edit()
                .putInt(KEY_ID, id)
                .putString(KEY_EMAIL, email)
                .putString(KEY_NAME, name)
                .apply();
    }

    public boolean isLoggedIn() {
        return prefs.getInt(KEY_ID, -1) != -1;
    }

    public int getUserId() {
        return prefs.getInt(KEY_ID, -1);
    }

    public String getEmail() {
        return prefs.getString(KEY_EMAIL, "");
    }

    public String getName() {
        return prefs.getString(KEY_NAME, "");
    }

    public void clearSession() {
        prefs.edit().clear().apply();
    }
}
