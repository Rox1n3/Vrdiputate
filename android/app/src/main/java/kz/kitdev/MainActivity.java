package kz.kitdev;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

import kz.kitdev.auth.LoginActivity;
import kz.kitdev.auth.TermsActivity;
import kz.kitdev.chat.ChatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            // Если пользователь ещё не принял соглашение — показываем его
            if (!TermsActivity.isAccepted(this)) {
                startActivity(new Intent(this, TermsActivity.class));
            } else {
                startActivity(new Intent(this, ChatActivity.class));
            }
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
        finish();
    }
}
