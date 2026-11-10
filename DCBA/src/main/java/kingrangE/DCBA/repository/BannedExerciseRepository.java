package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.BannedExercise;
import kingrangE.DCBA.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BannedExerciseRepository extends JpaRepository<BannedExercise, Long> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from BannedExercise p where p.user.id = :userId and p.exercise.id = :exerciseId")
    int deletePreference(Long userId, Long exerciseId);

    @org.springframework.data.jpa.repository.Query("""
            select p.exercise.id from BannedExercise p
            where p.user.id = :userId and p.exercise.id in :exerciseIds
            """)
    java.util.List<Long> findExerciseIds(Long userId, java.util.List<Long> exerciseIds);

    @org.springframework.data.jpa.repository.Query("""
            select e from BannedExercise preference join preference.exercise e
            where preference.user.id = :userId
              and (:subject is null or e.subject = :subject)
              and (:level is null or e.level = :level)
            """)
    Slice<kingrangE.DCBA.domain.Exercise> findExercises(Long userId,
            kingrangE.DCBA.domain.Subject subject, kingrangE.DCBA.domain.Level level, Pageable pageable);

    /**
     * User가 밴한 전체 문제 Page 찾기
     * @param user 유저 객체
     * @param pageable 페이지 정보
     * @return 페이지
     */
    Page<BannedExercise> findAllByUser(User user, Pageable pageable);

    /**
     * User와 Exercise 정보를 기반으로 밴한 문제인지 찾음 (선택하지 않았다면, Null 반환)
     * @param user 유저 객체
     * @param exercise 문제 객체
     * @return 선택된 문제 객체 (Optional)
     */
    java.util.Optional<BannedExercise> findByUserAndExercise(User user, kingrangE.DCBA.domain.Exercise exercise);
}
