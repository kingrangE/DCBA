package kingrangE.DCBA.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@ToString // 객체 print 확인을 위한 toString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Exercise {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT") // 질문과 답변은 길이 제한이 없어야 함
    private String question;
    @Column(columnDefinition = "TEXT")
    private String answer;

    @Enumerated(EnumType.STRING)
    private Subject subject;
    @Enumerated(EnumType.STRING)
    private Level level;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
