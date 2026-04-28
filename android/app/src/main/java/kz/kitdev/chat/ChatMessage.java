package kz.kitdev.chat;

import java.io.Serializable;

public class ChatMessage implements Serializable {
    public static final int TYPE_USER  = 0;
    public static final int TYPE_BOT   = 1;
    public static final int TYPE_IMAGE = 2;  // фото от пользователя

    public int     type;
    public String  text;
    public String  imageUri;   // локальный URI или URL из Firebase Storage (для TYPE_IMAGE)
    public String  question;   // парный вопрос для bot-сообщений (сохранение в историю)
    public boolean isLoading;

    public ChatMessage(int type, String text) {
        this.type = type;
        this.text = text;
        this.isLoading = false;
    }

    public static ChatMessage imageMessage(String uri) {
        ChatMessage m = new ChatMessage(TYPE_IMAGE, "");
        m.imageUri = uri;
        return m;
    }

    public static ChatMessage loading() {
        ChatMessage m = new ChatMessage(TYPE_BOT, "");
        m.isLoading = true;
        return m;
    }
}
