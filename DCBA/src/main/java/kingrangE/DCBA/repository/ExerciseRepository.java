package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;

import javax.swing.text.html.Option;
import java.util.List;
import java.util.Optional;

public interface ExerciseRepository {
    String save(Exercise exercise); //문제 정보 받아 저장
    Optional<Exercise> findByExerciseNumber(Integer exerciseNumber); // 문제 번호를 전달 받아 문제 조회
    Optional<List<Exercise>> findAllExercises(); // 모든 문제 조회
    Optional<List<Exercise>> findExercisesBySubject(Subject subject); // 과목을 선택해서 조회
    Optional<List<Exercise>> findExercisesByLevel(Level level); // 수준을 선택해서 조회
    Optional<List<Exercise>> findExercisesBySubjectAndLevel(Subject subject, Level level); // 과목과 수준을 모두 선택하여 조회
}
