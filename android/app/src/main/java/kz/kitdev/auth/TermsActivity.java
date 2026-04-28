package kz.kitdev.auth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import kz.kitdev.R;
import kz.kitdev.chat.ChatActivity;

public class TermsActivity extends AppCompatActivity {

    private static final String PREFS_NAME  = "app_prefs";
    private static final String KEY_TOS     = "tos_accepted_";

    /** Проверяет, принял ли текущий пользователь соглашение */
    public static boolean isAccepted(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return false;
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_TOS + user.getUid(), false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terms);

        TextView  tvText     = findViewById(R.id.tvTermsText);
        CheckBox  cbAccept   = findViewById(R.id.cbAccept);
        Button    btnContinue = findViewById(R.id.btnContinue);

        tvText.setText(buildTermsText());

        // Кнопка изначально неактивна
        btnContinue.setEnabled(false);
        btnContinue.setAlpha(0.45f);

        cbAccept.setOnCheckedChangeListener((btn, checked) -> {
            btnContinue.setEnabled(checked);
            btnContinue.animate().alpha(checked ? 1f : 0.45f).setDuration(200).start();
        });

        btnContinue.setOnClickListener(v -> {
            saveAcceptance();
            goToChat();
        });
    }

    private void saveAcceptance() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        SharedPreferences.Editor editor =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_TOS + user.getUid(), true);
        editor.apply();
    }

    private void goToChat() {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        // Плавное исчезновение
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ── Формируем текст соглашения со стилями ────────────────
    private SpannableStringBuilder buildTermsText() {
        SpannableStringBuilder sb = new SpannableStringBuilder();

        appendBold(sb, "Используя приложение «Виртуальный депутат», вы соглашаетесь с условиями настоящего соглашения.\n\n");

        appendNormal(sb,
            "Приложение предоставляет пользователям возможность отправки обращений (заявок) " +
            "представителям органов власти или иным уполномоченным лицам.\n\n");

        appendNormal(sb,
            "Приложение не является государственным органом и не принимает решений по обращениям " +
            "пользователей. Все заявки передаются третьим лицам (депутатам или ответственным лицам) " +
            "для дальнейшего рассмотрения.\n\n");

        appendNormal(sb,
            "Ответственность за рассмотрение, обработку и последствия принятых заявок несут " +
            "исключительно лица, получающие и обрабатывающие данные обращения.\n\n");

        appendNormal(sb,
            "Приложение не гарантирует рассмотрение заявки, сроки ответа или результат её обработки.\n\n");

        appendBold(sb, "Администрация приложения не несёт ответственности за:\n");
        appendBullet(sb, "действия или бездействие третьих лиц;");
        appendBullet(sb, "содержание ответов на заявки;");
        appendBullet(sb, "возможные убытки, ущерб или иные последствия, возникшие в результате использования сервиса.");

        sb.append("\n\n");
        appendNormal(sb,
            "Пользователь обязуется предоставлять достоверную информацию и не использовать " +
            "сервис в противоправных целях.\n\n");

        appendBold(sb,
            "Используя приложение, вы подтверждаете, что понимаете и принимаете данные условия.");

        return sb;
    }

    private void appendBold(SpannableStringBuilder sb, String text) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void appendNormal(SpannableStringBuilder sb, String text) {
        sb.append(text);
    }

    private void appendBullet(SpannableStringBuilder sb, String text) {
        int start = sb.length();
        sb.append("  • " + text + "\n");
    }
}
