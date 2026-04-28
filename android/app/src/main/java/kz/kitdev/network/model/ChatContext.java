package kz.kitdev.network.model;

public class ChatContext {
    public String lastUserQuestion;
    public String lastAssistantAnswer;

    public ChatContext(String lastUserQuestion, String lastAssistantAnswer) {
        this.lastUserQuestion = lastUserQuestion;
        this.lastAssistantAnswer = lastAssistantAnswer;
    }
}
