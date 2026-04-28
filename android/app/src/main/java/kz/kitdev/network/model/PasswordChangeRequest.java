package kz.kitdev.network.model;

public class PasswordChangeRequest {
    public String current;
    public String next;

    public PasswordChangeRequest(String current, String next) {
        this.current = current;
        this.next = next;
    }
}
