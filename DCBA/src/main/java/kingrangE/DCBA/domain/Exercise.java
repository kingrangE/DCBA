package kingrangE.DCBA.domain;

public class Exercise {
    private Long num;
    private String title;
    private String best_answer;
    private Subject subject;
    private Level level;

    public Long getNum() {
        return num;
    }

    public void setNum(Long num) {
        this.num = num;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBest_answer() {
        return best_answer;
    }

    public void setBest_answer(String best_answer) {
        this.best_answer = best_answer;
    }


    public Subject getSubject() {
        return subject;
    }

    public void setSubject(Subject subject) {
        this.subject = subject;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }
}
