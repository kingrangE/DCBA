package kingrangE.DCBA.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "exercise_feedback_rubric")
public class ExerciseFeedbackRubric {
    @Id
    private Long exerciseId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercise_id", foreignKey = @ForeignKey(name = "fk_feedback_rubric_exercise"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Exercise exercise;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON", nullable = false)
    private String criteriaJson;
}
