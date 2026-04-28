package kz.kitdev;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import kz.kitdev.auth.LoginActivity;
import kz.kitdev.chat.ChatActivity;
import kz.kitdev.databinding.ActivityHomeBinding;
import kz.kitdev.profile.ProfileActivity;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // HomeActivity больше не используется — перенаправляем в ChatActivity
        startActivity(new Intent(this, ChatActivity.class));
        finish();
    }
}
