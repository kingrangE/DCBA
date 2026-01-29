package kingrangE.DCBA.domain;

public enum Level {
    EASY(1),
    MEDIUM(2),
    HARD(3);

    private final int level;

    Level(int level){
        this.level = level;
    }

    public int getLevel(){
        return level;
    }
}
