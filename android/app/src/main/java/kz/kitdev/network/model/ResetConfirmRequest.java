package kz.kitdev.network.model;

public class ResetConfirmRequest {
    public String token;
    public String password;

    public ResetConfirmRequest(String token, String password) {
        this.token = token;
        this.password = password;
    }
}
