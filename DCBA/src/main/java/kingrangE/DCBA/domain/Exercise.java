package kingrangE.DCBA.domain;

public class Exercise {
    private Integer num;
    private String title;
    private String best_answer;
    private Subject subject;
    private Level level;

    public Exercise(Integer num, String title, String best_answer, Subject subject, Level level) {
        this.num = num;
        this.title = title;
        this.best_answer = best_answer;
        this.subject = subject;
        this.level = level;
    }

    public Integer getNum() {
        return num;
    }

    public void setNum(Integer num) {
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
