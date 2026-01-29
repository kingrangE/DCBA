package kingrangE.DCBA.domain;

public enum Subject {
    COMPUTER_ARCHITECTURE("컴퓨터 구조"),
    OPERATING_SYSTEM("운영체제"),
    COMPUTER_NETWORK("컴퓨터 네트워크"),
    DATA_STRUCTURE("자료구조"),
    ALGORITHM("알고리즘"),
    DATABASE("데이터베이스");

    private final String subjectName;
    Subject(String subjectName) {
        this.subjectName = subjectName;
    }

    public String getSubjectName() {
        return subjectName;
    }
}
