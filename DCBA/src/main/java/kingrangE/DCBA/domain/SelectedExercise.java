package kingrangE.DCBA.domain;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import lombok.*;

@Entity // JPA 이용을 위한 Entity 설정
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) //JPA를 위한 기본 생성자
@AllArgsConstructor

public class SelectedExercise {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id")
    private Exercise exercise;

    @Nullable // 처음엔 모범 답변을 보여줘야 하므로 NUll가능
    @Column(columnDefinition = "TEXT") // 글자수 많게 가능하도록
    private String custom_answer; // Null이면 원본 답안 보여주고, 유저가 수정했으면 수정 답안 보여주는 용도

    @Builder // id, nullable 제외한 Builder
    public SelectedExercise(User user, Exercise exercise){
        this.user = user;
        this.exercise = exercise;
    }
}
