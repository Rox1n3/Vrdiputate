package kz.kitdev.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;

import kz.kitdev.R;
import kz.kitdev.chat.ChatActivity;
import kz.kitdev.databinding.ActivityLoginBinding;
import kz.kitdev.fcm.AppFirebaseMessagingService;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();

        binding.btnLogin.setOnClickListener(v -> attemptLogin());

        binding.tvRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));

        binding.tvForgotPassword.setOnClickListener(v ->
                startActivity(new Intent(this, ResetPasswordActivity.class)));
    }

    private void attemptLogin() {
        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (email.isEmpty()) {
            binding.etEmail.setError(getString(R.string.error_email_required));
            return;
        }
        if (password.isEmpty()) {
            binding.etPassword.setError(getString(R.string.error_password_required));
            return;
        }

        setLoading(true);

        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        // Сохраняем FCM-токен сразу после входа
                        FirebaseMessaging.getInstance().getToken()
                                .addOnSuccessListener(AppFirebaseMessagingService::saveTokenToFirestore);
                        goHome();
                    } else {
                        showError(getString(R.string.error_invalid_credentials));
                    }
                });
    }

    private void goHome() {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void setLoading(boolean loading) {
        binding.btnLogin.setEnabled(!loading);
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
