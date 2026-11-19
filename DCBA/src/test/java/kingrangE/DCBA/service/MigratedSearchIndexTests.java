package kingrangE.DCBA.service;

import jakarta.persistence.EntityManager;
import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.repository.ExerciseRepository;
import kingrangE.DCBA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(ExerciseService.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("prod")
@EnabledIfEnvironmentVariable(named = "SEARCH_INDEX_INTEGRATION_TEST", matches = "true")
class MigratedSearchIndexTests {
    @Autowired ExerciseService service;
    @Autowired UserRepository users;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ExerciseRepository exercises;

    @Test
    void switchesToCompletedIndexAndPreservesSearchResultsAcrossWritesAndCursors() {
        Long user = users.saveAndFlush(new User("idx-user", "hash")).getId();
        Long preexisting = jdbc.queryForObject("SELECT id FROM exercise WHERE question = '교착 상태 기존 문항'", Long.class);
        String query = "교착 상태";

        assertThat(jdbc.update("DELETE FROM schema_migration WHERE version = 6")).isOne();
        assertThat(ids(service.getExercises(user, null, null, query, null, null))).containsExactly(preexisting);
        var descending = PageRequest.of(0, 9, Sort.by(Sort.Direction.DESC, "e.id"));
        verify(exercises).findVisibleExercises(user, null, null, query, null, null, descending);

        jdbc.update("INSERT INTO schema_migration (version) VALUES (6)");
        assertThat(ids(service.getExercises(user, null, null, query, null, null))).containsExactly(preexisting);
        verify(exercises).findIndexedExercises(user, query, null, null,
                PageRequest.of(0, 9, Sort.by(Sort.Direction.DESC, "exercise_id")));

        for (int i = 0; i < 11; i++) insert("교착 상태 사례 " + i);
        insert("교착 상태의 복구 절차");
        Long exact = insert("교착 상태 운영 절차");
        Long blocked = insert("교착 상태 숨김");
        service.banExercise(user, blocked);
        Long edited = insert("수정 전 deadlock %_\\");
        for (String keyword : List.of("DEADLOCK", "%_\\", "교착 상태 없는 접미어")) {
            assertThat(ids(service.getExercises(user, null, null, keyword, null, null)))
                    .containsExactlyElementsOf(ids(exercises.findVisibleExercises(user, null, null, keyword, null, null, descending)));
        }
        assertThat(ids(service.getExercises(user, null, null, "교착 상태 운영 절차", null, null))).containsExactly(exact);
        assertThat(ids(service.getExercises(user, null, null, "DEADLOCK", null, null))).containsExactly(edited);
        jdbc.update("UPDATE exercise SET question = ? WHERE id = ?", "교착 상태 수정됨", edited);
        entities.clear();
        assertThat(ids(service.getExercises(user, null, null, "DEADLOCK", null, null))).isEmpty();
        assertThat(ids(service.getExercises(user, null, null, "상태 수정됨", null, null))).containsExactly(edited);

        var first = service.getExercises(user, null, null, query, null, null);
        var next = service.getExercises(user, null, null, query, first.getContent().getLast().getId(), null);
        var back = service.getExercises(user, null, null, query, null, next.getContent().getFirst().getId());
        assertThat(ids(first)).containsExactlyElementsOf(ids(exercises.findVisibleExercises(user, null, null, query, null, null, descending)))
                .startsWith(edited).doesNotContain(blocked);
        assertThat(ids(next)).containsExactlyElementsOf(ids(exercises.findVisibleExercises(user, null, null, query,
                first.getContent().getLast().getId(), null, descending))).endsWith(preexisting);
        assertThat(ids(back).reversed()).containsExactlyElementsOf(ids(first));

        service.deleteExerciseFromPool(edited);
        entities.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exercise_search_gram WHERE exercise_id = ?", Long.class, edited)).isZero();
        assertThat(ids(service.getExercises(user, null, null, "상태 수정됨", null, null))).isEmpty();
    }

    private Long insert(String question) {
        return exercises.saveAndFlush(Exercise.builder().subject(Subject.OPERATING_SYSTEM).level(Level.MEDIUM)
                .question(question).answer("해설").build()).getId();
    }

    private List<Long> ids(Slice<Exercise> page) {
        return page.stream().map(Exercise::getId).toList();
    }
}
