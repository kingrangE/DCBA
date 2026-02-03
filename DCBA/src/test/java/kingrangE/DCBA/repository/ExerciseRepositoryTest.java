package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;

class ExerciseRepositoryTest {

    ExerciseRepository exerciseRepository;

    @BeforeEach
    void setup() {
        exerciseRepository = new MemoryExerciseRepository(); // 생각해보니까 어차피 DI로 해도 Config를 항상 수정해줘야함. 그냥 이걸로하는게 훨씬 가벼움3
    }

    @Test
    @DisplayName("문제 저장 및 조회")
    void save() {
        // given
        Exercise exercise = new Exercise(1, "OSI 7계층", "7계층은 Application계층", Subject.COMPUTER_NETWORK, Level.EASY);
        // when
        Integer num = exerciseRepository.save(exercise); // 저장 및 문제 번호 반환

        // then
        Exercise findedExercise = exerciseRepository.findByExerciseNumber(num).orElse(null); // 잘 저장됐는지 확인
        Assertions.assertThat(exercise).isEqualTo(findedExercise); // 저장한거랑 찾은게 같나?
    }

    @Test
    @DisplayName("레벨 기반 조회 (목록)")
    void findByLevel() {
        // given
        // 문제 생성 및 저장
        exerciseRepository.save(getExercise(1,Subject.COMPUTER_NETWORK, Level.EASY));
        exerciseRepository.save(getExercise(2,Subject.ALGORITHM, Level.MEDIUM));
        exerciseRepository.save(getExercise(3,Subject.COMPUTER_NETWORK, Level.MEDIUM));
        // when
        List<Exercise> mediumExercises = exerciseRepository.findExercisesByLevel(Level.MEDIUM); // 미디엄 조회
        List<Exercise> easyExercises = exerciseRepository.findExercisesByLevel(Level.EASY); // 이지 조회
        // then
        Assertions.assertThat(mediumExercises).hasSize(2); // 2개 갖고 있나?
        Assertions.assertThat(easyExercises).hasSize(1); // 1개 갖고 있나?
    }

    @Test
    @DisplayName("과목 기반 조회 (목록)")
    void findBySubject() {
        // given
        exerciseRepository.save(getExercise(1,Subject.COMPUTER_NETWORK, Level.EASY));
        exerciseRepository.save(getExercise(2,Subject.ALGORITHM, Level.MEDIUM));
        exerciseRepository.save(getExercise(3,Subject.COMPUTER_NETWORK, Level.MEDIUM));
        // when
        List<Exercise> networks = exerciseRepository.findExercisesBySubject(Subject.COMPUTER_NETWORK); // 컴네 조회
        List<Exercise> algorithms = exerciseRepository.findExercisesBySubject(Subject.ALGORITHM); // 알고 조회
        // then
        Assertions.assertThat(networks).hasSize(2); // 2개 갖고 있나?
        Assertions.assertThat(algorithms).hasSize(1); // 1개 갖고 있나?
    }

    @Test
    @DisplayName("과목,레벨 기반 조회 (목록)")
    void findByLevelAndSubject() {
        // given
        exerciseRepository.save(getExercise(1,Subject.COMPUTER_NETWORK, Level.EASY));
        exerciseRepository.save(getExercise(2,Subject.ALGORITHM, Level.MEDIUM));
        exerciseRepository.save(getExercise(3,Subject.COMPUTER_NETWORK, Level.MEDIUM));
        exerciseRepository.save(getExercise(4,Subject.ALGORITHM, Level.MEDIUM));
        // when
        List<Exercise> networksMedium = exerciseRepository.findExercisesBySubjectAndLevel(Subject.COMPUTER_NETWORK,
                Level.MEDIUM); // 컴네+미디엄 조회
        List<Exercise> algorithmsMedium = exerciseRepository.findExercisesBySubjectAndLevel(Subject.ALGORITHM,
                Level.MEDIUM); // 알고+미디엄 조회
        // then
        Assertions.assertThat(networksMedium).hasSize(1); // 1개 갖고 있나?
        Assertions.assertThat(algorithmsMedium).hasSize(2); // 2개 갖고 있나?
    }

    @Test
    @DisplayName("모든 문제 조회")
    void delete() {
        // given
        exerciseRepository.save(getExercise(1,Subject.COMPUTER_NETWORK, Level.EASY));
        exerciseRepository.save(getExercise(2,Subject.ALGORITHM, Level.MEDIUM));
        exerciseRepository.save(getExercise(3,Subject.COMPUTER_NETWORK, Level.MEDIUM));
        exerciseRepository.save(getExercise(4,Subject.ALGORITHM, Level.MEDIUM));
        // when
        List<Exercise> allExercises = exerciseRepository.findAllExercises();
        // then
        Assertions.assertThat(allExercises).hasSize(4); // 4개 있나?
    }

    Exercise getExercise(Integer num,Subject subject,Level level){
        return new Exercise(num,"Test","Test",subject,level);
    }
}

