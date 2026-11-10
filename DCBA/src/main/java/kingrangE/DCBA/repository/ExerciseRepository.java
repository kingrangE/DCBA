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
              and not exists (select b.id from BannedExercise b
                              where b.user.id = :userId and b.exercise = e)
            """)
    Slice<Exercise> findVisibleExercises(Long userId, Subject subject, Level level, Pageable pageable);

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
