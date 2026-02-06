package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.SelectedExercise;
import kingrangE.DCBA.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SelectedExerciseRepository extends JpaRepository<SelectedExercise, Long> {
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
