package kz.kitdev.history;

public class HistoryItem {
    public String id;
    public String question;
    public String answer;
    public String lang;
    public long timestampMillis;

    public HistoryItem() {}

    public HistoryItem(String id, String question, String answer, String lang, long timestampMillis) {
        this.id = id;
        this.question = question;
        this.answer = answer;
        this.lang = lang;
        this.timestampMillis = timestampMillis;
    }
}
