package kz.kitdev.network.model;

public class RegisterRequest {
    public String name;
    public String email;
    public String password;

    public RegisterRequest(String name, String email, String password) {
        this.name = name;
        this.email = email;
        this.password = password;
    }
}
