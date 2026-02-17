package kingrangE.DCBA.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "CHAR(10)", unique = true) // 글자 수 제한 및 중복 금지
    private String name; // 로그인을 위해 사용할 이름

    @Column(nullable = false, length = 60) // BCrypt는 길이가 60으로 고정되어 있다.
    private String password; // 나중에 보안 되도록 변경해야 한다. (로그인해서 할 수 있는게 조회뿐이므로 일단은 그냥 생으로 저장)

    // 가입 시간 (수정 불가)
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true)
    private String slackId;

    @Builder
    public User(String name, String password) {
        this.name = name;
        this.password = password;
    }

    public void updateSlackId(String slackId) {
        this.slackId = slackId;
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
