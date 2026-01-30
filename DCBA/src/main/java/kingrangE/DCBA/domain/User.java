package kingrangE.DCBA.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class User {
    private String id; // User Slack ID;
    private String name; // 로그인을 위해 사용할 이름
    private String password; // 나중에 보안 되도록 변경해야 한다. (로그인해서 할 수 있는게 조회뿐이므로 일단은 그냥 생으로 저장)
    private List<String> mainLanguages; // User Main Language;
    private Map<String,Integer> subjectLevels; // User의 각 과목 Level
    private List<Integer> exercises; // User가 지금까지 받은 문제 번호 목록
    private List<Integer> blacklist;

    public User(String id, String name, String password, List<String> mainLanguages) {
        // 기본 회원가입 ( 과목 난이도 설정 불가, 문제 받은목록, 차단목록 초기화)
        this.id = id;
        this.name = name;
        this.password = password;
        this.mainLanguages = mainLanguages;
        this.subjectLevels = new HashMap<>(Map.of(
                Subject.DATA_STRUCTURE.getSubjectName(),1,
                Subject.ALGORITHM.getSubjectName(),1,
                Subject.COMPUTER_ARCHITECTURE.getSubjectName(),1,
                Subject.OPERATING_SYSTEM.getSubjectName(),1,
                Subject.DATABASE.getSubjectName(),1,
                Subject.COMPUTER_NETWORK.getSubjectName(),1
        ));
        this.exercises = new ArrayList<>();
        this.blacklist = new ArrayList<>();
    }

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

    public Map<String, Integer> getSubjectLevels() {
        return subjectLevels;
    }

    public void setSubjectLevels(Map<String, Integer> subjectLevels) {
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
