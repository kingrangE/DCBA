package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.SelectedExercise;
import kingrangE.DCBA.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SelectedExerciseRepository extends JpaRepository<SelectedExercise, Long> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from SelectedExercise p where p.user.id = :userId and p.exercise.id = :exerciseId")
    int deletePreference(Long userId, Long exerciseId);

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
            """)
    Page<kingrangE.DCBA.domain.Exercise> findExercises(Long userId,
            kingrangE.DCBA.domain.Subject subject, kingrangE.DCBA.domain.Level level, Pageable pageable);

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
