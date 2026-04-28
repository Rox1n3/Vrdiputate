package kz.kitdev.auth;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

import kz.kitdev.R;
import kz.kitdev.databinding.ActivityResetPasswordBinding;

public class ResetPasswordActivity extends AppCompatActivity {

    private ActivityResetPasswordBinding binding;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityResetPasswordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Скрываем шаг 2 — Firebase обрабатывает сброс через ссылку в письме
        binding.step2Layout.setVisibility(View.GONE);

        binding.btnSendEmail.setOnClickListener(v -> sendResetEmail());
    }

    private void sendResetEmail() {
        String email = binding.etEmail.getText().toString().trim();
        if (email.isEmpty()) {
            binding.etEmail.setError(getString(R.string.error_email_required));
            return;
        }

        setLoadingStep1(true);

        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    setLoadingStep1(false);
                    if (task.isSuccessful()) {
                        Toast.makeText(this, getString(R.string.reset_email_sent), Toast.LENGTH_LONG).show();
                        finish();
                    } else {
                        Toast.makeText(this, getString(R.string.error_reset_failed), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoadingStep1(boolean loading) {
        binding.btnSendEmail.setEnabled(!loading);
        binding.progressBar1.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
