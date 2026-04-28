package kz.kitdev.network.model;

public class ChatRequest {
    public String prompt;
    public String mode;    // "guest" или "user"
    public String lang;    // "ru", "kk", "en"
    public ChatContext context;

    public ChatRequest(String prompt, String mode, String lang, ChatContext context) {
        this.prompt = prompt;
        this.mode = mode;
        this.lang = lang;
        this.context = context;
    }
}
