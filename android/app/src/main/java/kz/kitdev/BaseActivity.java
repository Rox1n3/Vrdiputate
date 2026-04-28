package kz.kitdev;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import kz.kitdev.chat.LangManager;

/**
 * Базовый класс для всех Activity.
 * Применяет сохранённый язык к контексту, чтобы строки отображались на выбранном языке.
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = LangManager.get(newBase);
        Locale locale = LangManager.toLocale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.setLocale(locale);
        Context context = newBase.createConfigurationContext(config);
        super.attachBaseContext(context);
    }
}
