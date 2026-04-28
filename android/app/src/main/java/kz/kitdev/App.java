package kz.kitdev;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessaging;

import kz.kitdev.fcm.AppFirebaseMessagingService;

public class App extends Application {

    private static App instance;

    /** Глобальный доступ к контексту приложения (для StorageUploader и других утилит). */
    public static App get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Firebase инициализируется автоматически из google-services.json
        ThemeManager.init(this);

        // Создаём канал уведомлений при старте — необходимо для Android 8+
        // Должен существовать до первого системного уведомления (когда приложение убито)
        createNotificationChannel();

        // Получаем текущий FCM-токен и сохраняем в Firestore (если пользователь вошёл)
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> AppFirebaseMessagingService.saveTokenToFirestore(token));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    AppFirebaseMessagingService.CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(getString(R.string.notification_channel_desc));
            channel.enableLights(true);
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
