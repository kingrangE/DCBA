package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
@Sql("/data.sql") // SQL에 테스트용 데이터 삽입 후 실행
class ExerciseRepositoryTest {

    @Autowired
    ExerciseRepository exerciseRepository;

    @Test
    @DisplayName("레벨 기반 조회 (목록)")
    void findByLevel() {
        // given
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
        // when
        List<Exercise> networks = exerciseRepository.findExercisesBySubject(Subject.COMPUTER_NETWORK); // 컴네 조회
        List<Exercise> algorithms = exerciseRepository.findExercisesBySubject(Subject.ALGORITHM); // 알고 조회
        // then
        Assertions.assertThat(networks).hasSize(2); // 2개 갖고 있나?
        Assertions.assertThat(algorithms).hasSize(2); // 2개 갖고 있나?
    }

    @Test
    @DisplayName("과목,레벨 기반 조회 (목록)")
    void findByLevelAndSubject() {
        //given
        // when
        List<Exercise> networksMedium = exerciseRepository.findExercisesBySubjectAndLevel(Subject.COMPUTER_NETWORK,
                Level.MEDIUM); // 컴네+미디엄 조회
        List<Exercise> algorithmsMedium = exerciseRepository.findExercisesBySubjectAndLevel(Subject.ALGORITHM,
                Level.MEDIUM); // 알고+미디엄 조회
        // then
        Assertions.assertThat(networksMedium).hasSize(1); // 1개 갖고 있나?
        Assertions.assertThat(algorithmsMedium).hasSize(1); // 2개 갖고 있나?
    }

    @Test
    @DisplayName("모든 문제 조회")
    void delete() {
        // given
        // when
        List<Exercise> allExercises = exerciseRepository.findAll();
        // then
        Assertions.assertThat(allExercises).hasSize(4); // 4개 있나?
    }
}

