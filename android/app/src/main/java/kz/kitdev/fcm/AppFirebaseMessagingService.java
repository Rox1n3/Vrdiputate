package kz.kitdev.fcm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Map;

import kz.kitdev.R;
import kz.kitdev.analytics.AnalyticsActivity;

public class AppFirebaseMessagingService extends FirebaseMessagingService {

    public static final String CHANNEL_ID = "status_updates";
    // Ключ совпадает с data-payload ключом FCM — при тапе на системное уведомление
    // Android FCM SDK кладёт data-поля в intent extras с теми же именами
    public static final String EXTRA_HIGHLIGHT_ID = "complaint_id";

    // ── Token refresh ─────────────────────────────────────────────────────

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        saveTokenToFirestore(token);
    }

    // ── Incoming message ──────────────────────────────────────────────────

    // onMessageReceived вызывается ТОЛЬКО когда приложение в foreground.
    // В background/killed системный FCM SDK (Google Play Services) сам отображает
    // уведомление из notification-payload и открывает AnalyticsActivity через click_action.
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        Map<String, String> data = message.getData();
        String complaintId = data.get("complaint_id");

        // Читаем текст из notification-объекта (там title/body от бэкенда)
        RemoteMessage.Notification notif = message.getNotification();
        String title = (notif != null && notif.getTitle() != null)
                ? notif.getTitle()
                : getString(R.string.notification_status_title);
        String body  = (notif != null && notif.getBody() != null)
                ? notif.getBody()
                : getString(R.string.notification_status_body);

        createNotificationChannel();

        // Tap opens AnalyticsActivity and passes the complaint ID for highlight
        Intent intent = new Intent(this, AnalyticsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (complaintId != null && !complaintId.isEmpty()) {
            intent.putExtra(EXTRA_HIGHLIGHT_ID, complaintId);
        }

        int reqCode = complaintId != null ? complaintId.hashCode() : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            int notifId = complaintId != null ? complaintId.hashCode() : (int) System.currentTimeMillis();
            nm.notify(notifId, builder.build());
        }
    }

    // ── Notification channel (API 26+) ────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.notification_channel_name);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(getString(R.string.notification_channel_desc));
            channel.enableLights(true);
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    // ── Save FCM token to Firestore ───────────────────────────────────────

    public static void saveTokenToFirestore(String token) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || token == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("fcmToken", token);
        FirebaseFirestore.getInstance()
                .collection("users").document(user.getUid())
                .update(data)
                .addOnFailureListener(e -> {
                    // User document may not have fcmToken field yet — try set with merge
                    FirebaseFirestore.getInstance()
                            .collection("users").document(user.getUid())
                            .set(data, com.google.firebase.firestore.SetOptions.merge());
                });
    }
}
