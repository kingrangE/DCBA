package kingrangE.DCBA.domain;

import java.util.List;
import java.util.Map;

public class User {
    private String id; // User Slack ID;
    private List<String> mainLanguage; // User Main Language;
    private List<Map<String,Integer>> subjectLevels; // User의 각 과목 Level
    private List<Integer> exercises; // User가 지금까지 받은 문제 번호 목록

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getMainLanguage() {
        return mainLanguage;
    }

    public void setMainLanguage(List<String> mainLanguage) {
        this.mainLanguage = mainLanguage;
    }

    public List<Map<String, Integer>> getLevels() {
        return subjectLevels;
    }

    public void setLevels(List<Map<String, Integer>> subjectLevels) {
        this.subjectLevels = subjectLevels;
    }

    public List<Integer> getExercises() {
        return exercises;
    }

    public void setExercises(List<Integer> exercises) {
        this.exercises = exercises;
    }
}
