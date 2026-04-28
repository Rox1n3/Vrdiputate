package kz.kitdev.util;

import android.util.Log;

import java.util.Properties;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Отправляет заявку заявителя на email через Gmail SMTP.
 *
 * Настройка: в local.properties добавьте:
 *   email.sender=rox030308@gmail.com
 *   email.password=kdal wvrd kzje llhw
 *
 * App Password (не обычный пароль) создаётся в настройках Google-аккаунта:
 *   Аккаунт Google → Безопасность → Двухэтапная аутентификация → Пароли приложений
 */
public class EmailSender {

    public interface EmailCallback {
        void onSuccess();
        void onError(String error);
    }

    /**
     * Проверяет казахстанский номер телефона.
     * Верный формат: +7 7XX / 8 7XX / 7 7XX, итого 11 цифр.
     * Примеры: +77011234567, 87051234567, 77771234567
     */
    public static boolean isValidKzPhone(String phone) {
        if (phone == null) return false;
        // Оставляем только цифры
        String digits = phone.replaceAll("[^\\d]", "");
        // Нормализуем: 8XXXXXXXXXX → 7XXXXXXXXXX
        if (digits.startsWith("8") && digits.length() == 11) {
            digits = "7" + digits.substring(1);
        }
        // Должно быть ровно 11 цифр, начинаться с "77"
        return digits.length() == 11 && digits.startsWith("77");
    }

    public static void sendComplaint(
            String toEmail,
            String senderEmail,
            String senderPassword,
            String applicantName,
            String applicantPhone,
            String problem,
            String address,
            String timestamp,
            EmailCallback callback) {

        new Thread(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.host", "smtp.gmail.com");
                props.put("mail.smtp.port", "587");
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.ssl.trust", "smtp.gmail.com");

                Session session = Session.getInstance(props,
                        new javax.mail.Authenticator() {
                            @Override
                            protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                                return new javax.mail.PasswordAuthentication(
                                        senderEmail, senderPassword);
                            }
                        });

                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(senderEmail));
                message.addRecipient(Message.RecipientType.TO,
                        new InternetAddress(toEmail));
                message.setSubject(
                        "Новое обращение — Виртуальный Депутат Павлодарской области ("
                                + timestamp + ")");
                message.setText(
                        "НОВОЕ ОБРАЩЕНИЕ ЧЕРЕЗ ПРИЛОЖЕНИЕ\n"
                        + "==================================\n\n"
                        + "Дата и время: " + timestamp + "\n\n"
                        + "ФИО заявителя:  " + applicantName + "\n"
                        + "Телефон:        " + applicantPhone + "\n\n"
                        + "Проблема:       " + problem + "\n"
                        + "Адрес:          " + address + "\n\n"
                        + "--\n"
                        + "Сообщение сформировано автоматически через приложение\n"
                        + "\"Виртуальный Депутат — Павлодарская область\"\n",
                        "UTF-8");

                Transport.send(message);

                if (callback != null) callback.onSuccess();

            } catch (Exception e) {
                Log.e("EmailSender", "Ошибка отправки email", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }
}
