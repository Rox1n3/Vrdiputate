package kz.kitdev.network.model;

public class SaveRequest {
    public String question;
    public String answer;

    public SaveRequest(String question, String answer) {
        this.question = question;
        this.answer = answer;
    }
}
