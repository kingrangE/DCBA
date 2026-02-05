package kingrangE.DCBA.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


@Getter
@AllArgsConstructor
public class User {
    private String id; // User Slack ID;
    private String name; // 로그인을 위해 사용할 이름
    private String password; // 나중에 보안 되도록 변경해야 한다. (로그인해서 할 수 있는게 조회뿐이므로 일단은 그냥 생으로 저장)
    private List<String> mainLanguages; // User Main Language;
    private List<Integer> exercises; // User가 지금까지 받은 문제 번호 목록
    private List<Integer> blacklist;

    @Builder
    public User(String id, String name, String password, List<String> mainLanguages) {
        // 기본 회원가입 ( 과목 난이도 설정 불가, 문제 받은목록, 차단목록 초기화)
        this.id = id;
        this.name = name;
        this.password = password;
        this.mainLanguages = mainLanguages;
        this.exercises = new ArrayList<>();
        this.blacklist = new ArrayList<>();
    }
}
