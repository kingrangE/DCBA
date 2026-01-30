package kingrangE.DCBA.dto;

import java.util.List;

public class LoginForm {
    private String name; // User Nickname
    private String password; // User Password

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
