package kz.kitdev.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.messaging.FirebaseMessaging;

import kz.kitdev.R;
import kz.kitdev.chat.ChatActivity;
import kz.kitdev.databinding.ActivityRegisterBinding;
import kz.kitdev.fcm.AppFirebaseMessagingService;
import kz.kitdev.auth.TermsActivity;

public class RegisterActivity extends AppCompatActivity {

    private ActivityRegisterBinding binding;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();

        binding.btnRegister.setOnClickListener(v -> attemptRegister());

        binding.tvLogin.setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private void attemptRegister() {
        String name = binding.etName.getText().toString().trim();
        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (name.isEmpty()) {
            binding.etName.setError(getString(R.string.error_name_required));
            return;
        }
        if (email.isEmpty()) {
            binding.etEmail.setError(getString(R.string.error_email_required));
            return;
        }
        if (password.length() < 6) {
            binding.etPassword.setError(getString(R.string.error_password_short));
            return;
        }

        setLoading(true);

        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && auth.getCurrentUser() != null) {
                        UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                                .setDisplayName(name)
                                .build();
                        auth.getCurrentUser().updateProfile(profile)
                                .addOnCompleteListener(t -> {
                                    setLoading(false);
                                    // Сохраняем FCM-токен сразу после регистрации
                                    FirebaseMessaging.getInstance().getToken()
                                            .addOnSuccessListener(AppFirebaseMessagingService::saveTokenToFirestore);
                                    goHome();
                                });
                    } else {
                        setLoading(false);
                        showError(getString(R.string.error_register_failed));
                    }
                });
    }

    private void goHome() {
        // После регистрации всегда показываем пользовательское соглашение
        Intent intent = new Intent(this, TermsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void setLoading(boolean loading) {
        binding.btnRegister.setEnabled(!loading);
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
