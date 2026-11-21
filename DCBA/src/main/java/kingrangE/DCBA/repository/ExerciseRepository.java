package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import lombok.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// DCBA의 서비스에서는 문제를 개별적으로 확인할 일이 V1에서는 없다. -> Page 단위 관리를 위해 Page 이용
public interface ExerciseRepository extends JpaRepository<Exercise, Long> {

    /**
     * 전체 문제 페이지 반환
     * @param pageable 페이지 정보
     * @return 전체 문제들의 Pages
     */
    @Override
    @NonNull
    Page<Exercise> findAll(@NonNull Pageable pageable);

    /**
     * 과목 기반 필터링된 페이지 반환
     * @param subject 과목 (Enum)
     * @param pageable 페이지 정보
     * @return 과목 필터링된 Exercise들의 Pages
     */

    Page<Exercise> findBySubject(Subject subject, Pageable pageable);

    /**
     * 레벨 기반 필터링된 페이지 반환
     * @param level 레벨 (Enum)
     * @param pageable 페이지 정보
     * @return 레벨 필터링된 Exercise들의 Pages
     */
    Page<Exercise> findByLevel(Level level, Pageable pageable);

    /**
     * 과목과 레벨로 필터링한 페이지 반환
     * @param subject 과목 (Enum)
     * @param level 문제 수준 (Enum)
     * @param pageable 페이지 정보
     * @return Exercise들의 Pages
     */
    Page<Exercise> findBySubjectAndLevel(Subject subject, Level level, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            select e from Exercise e
            where (:subject is null or e.subject = :subject)
              and (:level is null or e.level = :level)
              and (:keyword is null or locate(lower(:keyword), lower(e.question)) > 0)
              and (:before is null or e.id < :before)
              and (:after is null or e.id > :after)
              and not exists (select b.id from BannedExercise b
                              where b.user.id = :userId and b.exercise = e)
            """)
    Slice<Exercise> findVisibleExercises(Long userId, Subject subject, Level level, String keyword,
                                         Long before, Long after, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            select e from Exercise e
            where (:subject is null or e.subject = :subject)
              and (:level is null or e.level = :level)
              and (cast(:keyword as string) is null or lower(e.question) like lower(cast(:keyword as string)) escape '!')
              and (:before is null or e.id < :before)
              and (:after is null or e.id > :after)
              and not exists (select b.id from BannedExercise b
                              where b.user.id = :userId and b.exercise = e)
            """)
    Slice<Exercise> findVisibleExercisesPg(Long userId, Subject subject, Level level, String keyword,
                                         Long before, Long after, Pageable pageable);

    // shortcut: shared head and tail grams still load candidates, probe an interior gram if those terms dominate latency.
    @Query(value = """
            SELECT e.* FROM exercise_search_gram g
            STRAIGHT_JOIN exercise_search_gram tail ON tail.exercise_id = g.exercise_id
              AND tail.gram = LOWER(RIGHT(CONVERT(:keyword USING utf8mb4), 3)) COLLATE utf8mb4_general_ci
            STRAIGHT_JOIN exercise e ON e.id = g.exercise_id
            WHERE g.gram = LOWER(SUBSTRING(CONVERT(:keyword USING utf8mb4), 1, 3)) COLLATE utf8mb4_general_ci
              AND (:before IS NULL OR g.exercise_id < :before)
              AND (:after IS NULL OR g.exercise_id > :after)
              AND LOCATE(LOWER(:keyword), LOWER(e.question)) > 0
              AND NOT EXISTS (SELECT 1 FROM banned_exercise b WHERE b.user_id = :userId AND b.exercise_id = e.id)
            """, nativeQuery = true)
    Slice<Exercise> findIndexedExercises(Long userId, String keyword, Long before, Long after, Pageable pageable);

    @Query("select e from Exercise e where (:before is null or e.id < :before) and (:after is null or e.id > :after)")
    Slice<Exercise> findPool(Long before, Long after, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from Exercise e where e.id = :id")
    Optional<Exercise> findForPreference(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exercise e where e.id = :id")
    Optional<Exercise> findForDeletion(Long id);

    @Modifying
    @Query(value = "INSERT INTO deleted_exercise (exercise_id) VALUES (:id)", nativeQuery = true)
    void recordDeletion(Long id);

    /**
     * 과목 기반 필터링 조회 (List 반환)
     * @param subject 과목 (Enum)
     * @return 필터링된 문제 전체 list
     */
    List<Exercise> findExercisesBySubject(Subject subject);

    /**
     * 레벨 기반 필터링 조회 (List 반환)
     * @param level 레벨 (Enum)
     * @return 필터링된 문제 전체 List
     */
    List<Exercise> findExercisesByLevel(Level level);

    /**
     * 레벨과 과목 기반 필터링 조회 (List 반환)
     * @param subject 과목 (Enum)
     * @param level 레벨 (Enum)
     * @return 필터링된 문제 전체 List
     */
    List<Exercise> findExercisesBySubjectAndLevel(Subject subject, Level level);
}
