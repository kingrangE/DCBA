package kingrangE.DCBA.domain;

import java.util.List;
import java.util.Map;

public class User {
    private String id; // User Slack ID;
    private String name; // 로그인을 위해 사용할 이름
    private String password; // 나중에 보안 되도록 변경해야 한다. (로그인해서 할 수 있는게 조회뿐이므로 일단은 그냥 생으로 저장)
    private List<String> mainLanguages; // User Main Language;
    private List<Map<String,Integer>> subjectLevels; // User의 각 과목 Level
    private List<Integer> exercises; // User가 지금까지 받은 문제 번호 목록
    private List<Integer> blacklist;

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

    public List<Map<String, Integer>> getSubjectLevels() {
        return subjectLevels;
    }

    public void setSubjectLevels(List<Map<String, Integer>> subjectLevels) {
        this.subjectLevels = subjectLevels;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getMainLanguages() {
        return mainLanguages;
    }

    public void setMainLanguages(List<String> mainLanguage) {
        this.mainLanguages = mainLanguage;
    }

    public List<Integer> getExercises() {
        return exercises;
    }

    public void setExercises(List<Integer> exercises) {
        this.exercises = exercises;
    }

    public List<Integer> getBlacklist() {
        return blacklist;
    }

    public void setBlacklist(List<Integer> blacklist) {
        this.blacklist = blacklist;
    }
}
