package kingrangE.DCBA.dto;

import java.util.List;

public class SignUpForm {
    private String id; // Slack ID
    private String name; // User Nickname
    private String password; // User Password
    private List<String> mainLanguages;

    public List<String> getMainLanguages() {
        return mainLanguages;
    }

    public void setMainLanguages(List<String> mainLanguages) {
        this.mainLanguages = mainLanguages;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

}
