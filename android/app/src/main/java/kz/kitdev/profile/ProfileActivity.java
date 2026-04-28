package kz.kitdev.profile;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import kz.kitdev.BaseActivity;
import kz.kitdev.R;
import kz.kitdev.auth.LoginActivity;
import kz.kitdev.databinding.ActivityProfileBinding;

public class ProfileActivity extends BaseActivity {

    private ActivityProfileBinding binding;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            binding.tvName.setText(user.getDisplayName() != null ? user.getDisplayName() : "");
            binding.tvEmail.setText(user.getEmail() != null ? user.getEmail() : "");
        }

        // Сбросить ошибки при вводе
        binding.etCurrentPassword.addTextChangedListener(new SimpleTextWatcher(() ->
                binding.tilCurrentPassword.setError(null)));
        binding.etNewPassword.addTextChangedListener(new SimpleTextWatcher(() ->
                binding.tilNewPassword.setError(null)));

        binding.btnChangePassword.setOnClickListener(v -> changePassword());
        binding.btnLogout.setOnClickListener(v -> logout());
    }

    private void changePassword() {
        String current = binding.etCurrentPassword.getText().toString().trim();
        String next = binding.etNewPassword.getText().toString().trim();

        // Красный текст ошибки под полем на выбранном языке
        if (current.isEmpty()) {
            binding.tilCurrentPassword.setError(getString(R.string.error_password_required));
            return;
        }
        binding.tilCurrentPassword.setError(null);

        if (next.length() < 6) {
            binding.tilNewPassword.setError(getString(R.string.error_password_short));
            return;
        }
        binding.tilNewPassword.setError(null);

        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getEmail() == null) return;

        setLoadingPassword(true);

        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), current);
        user.reauthenticate(credential).addOnCompleteListener(reauth -> {
            if (reauth.isSuccessful()) {
                user.updatePassword(next).addOnCompleteListener(update -> {
                    setLoadingPassword(false);
                    if (update.isSuccessful()) {
                        binding.etCurrentPassword.setText("");
                        binding.etNewPassword.setText("");
                        binding.tilCurrentPassword.setError(null);
                        binding.tilNewPassword.setError(null);
                        Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show();
                    } else {
                        binding.tilNewPassword.setError(getString(R.string.error_password_change_failed));
                    }
                });
            } else {
                setLoadingPassword(false);
                // Неверный текущий пароль — ошибка под первым полем
                binding.tilCurrentPassword.setError(getString(R.string.error_password_change_failed));
            }
        });
    }

    private void logout() {
        auth.signOut();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void setLoadingPassword(boolean loading) {
        binding.btnChangePassword.setEnabled(!loading);
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** Минимальный TextWatcher для сброса ошибки при вводе */
    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final Runnable action;
        SimpleTextWatcher(Runnable action) { this.action = action; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
        @Override public void afterTextChanged(android.text.Editable s) {}
    }
}
