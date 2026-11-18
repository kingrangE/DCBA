package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.SelectedExercise;
import kingrangE.DCBA.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SelectedExerciseRepository extends JpaRepository<SelectedExercise, Long> {
    String REVIEW_ROWS = """
            SELECT e.id, e.question, e.answer, e.subject, e.level, e.created_at AS createdAt,
                   s.review_again AS reviewAgain, r.criteria_json AS criteriaJson
            FROM selected_exercise s JOIN exercise e ON e.id = s.exercise_id
            LEFT JOIN exercise_feedback_rubric r ON r.exercise_id = e.id
            WHERE s.user_id = :userId AND NOT EXISTS (
                SELECT 1 FROM banned_exercise b WHERE b.user_id = s.user_id AND b.exercise_id = s.exercise_id
            )
            """;

    interface ReviewRow {
        Long getId();
        String getQuestion();
        String getAnswer();
        String getSubject();
        String getLevel();
        LocalDateTime getCreatedAt();
        boolean getReviewAgain();
        String getCriteriaJson();
    }

    @Query(value = REVIEW_ROWS + """
            AND (:subject IS NULL OR e.subject = :subject)
            AND (:level IS NULL OR e.level = :level)
            AND (:marked = FALSE OR s.review_again = TRUE)
            ORDER BY RAND() LIMIT 3
            """, nativeQuery = true)
    List<ReviewRow> sampleReview(Long userId, String subject, String level, boolean marked);

    @Query(value = REVIEW_ROWS + " AND e.id = :exerciseId", nativeQuery = true)
    Optional<ReviewRow> findReview(Long userId, Long exerciseId);

    @Modifying
    @Query(value = """
            UPDATE selected_exercise SET review_again = :enabled
            WHERE user_id = :userId AND exercise_id = :exerciseId AND NOT EXISTS (
                SELECT 1 FROM banned_exercise WHERE user_id = :userId AND exercise_id = :exerciseId
            )
            """, nativeQuery = true)
    int setReviewAgain(Long userId, Long exerciseId, boolean enabled);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from SelectedExercise p where p.user.id = :userId and p.exercise.id = :exerciseId")
    int deletePreference(Long userId, Long exerciseId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from SelectedExercise p where p.exercise.id = :exerciseId")
    void deleteForExercise(Long exerciseId);

    @org.springframework.data.jpa.repository.Query("""
            select p.exercise.id from SelectedExercise p
            where p.user.id = :userId and p.exercise.id in :exerciseIds
            """)
    java.util.List<Long> findExerciseIds(Long userId, java.util.List<Long> exerciseIds);

    @org.springframework.data.jpa.repository.Query("""
            select e from SelectedExercise preference join preference.exercise e
            where preference.user.id = :userId
              and (:subject is null or e.subject = :subject)
              and (:level is null or e.level = :level)
              and (:before is null or e.id < :before)
              and (:after is null or e.id > :after)
            """)
    Slice<kingrangE.DCBA.domain.Exercise> findExercises(Long userId,
            kingrangE.DCBA.domain.Subject subject, kingrangE.DCBA.domain.Level level, Long before, Long after, Pageable pageable);

    /**
     * User가 선택한 전체 문제 Page 찾기
     * @param user 유저 객체
     * @param pageable 페이지 정보
     * @return 페이지
     */
    Page<SelectedExercise> findAllByUser(User user, Pageable pageable);

    /**
     * User와 Exercise 정보를 기반으로 선택된 문제인지 찾음 (선택하지 않았다면, Null 반환)
     * @param user 유저 객체
     * @param exercise 문제 객체
     * @return 선택된 문제 객체 (Optional)
     */
    Optional<SelectedExercise> findByUserAndExercise(User user, Exercise exercise);
}
