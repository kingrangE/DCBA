package kingrangE.DCBA.domain;

public enum Level {
    EASY(1, "난이도 1 (기초)"),
    MEDIUM(2, "난이도 2 (기본)"),
    HARD(3, "난이도 3 (심화)");

    private final int level;
    private final String promptName;

    Level(int level, String promptName){
        this.level = level;
        this.promptName = promptName;
    }

    public int getLevel(){
        return level;
    }

    public String getPromptName() {
        return promptName;
    }
}
