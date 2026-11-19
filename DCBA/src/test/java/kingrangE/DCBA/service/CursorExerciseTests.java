package kingrangE.DCBA.service;

import jakarta.persistence.EntityManager;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Slice;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(ExerciseService.class)
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(replace = org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.ActiveProfiles("local")
class CursorExerciseTests {
    @Autowired ExerciseService service;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired EntityManager entityManager;

    private Slice<Exercise> read(String view, Long userId, Long before, Long after) {
        return switch (view) {
            case "selected" -> service.getSelectedExercises(userId, Subject.OPERATING_SYSTEM, Level.MEDIUM, null, before, after);
            case "banned" -> service.getBannedExercises(userId, Subject.OPERATING_SYSTEM, Level.MEDIUM, before, after);
            default -> service.getExercises(userId, Subject.OPERATING_SYSTEM, Level.MEDIUM, null, before, after);
        };
    }

    @Test
    void keywordSearchMatchesOnlyQuestionsAndRespectsOwnershipFiltersAndCursors() {
        Long owner = users.saveAndFlush(new User("searcher", "hash")).getId();
        Long other = users.saveAndFlush(new User("other", "hash")).getId();
        String key = "교착 상태";
        String literal = "%_\\";
        Exercise oldest = searchProblem("교착 상태와 deadlock " + literal, "해설", Level.MEDIUM);
        exercises.saveAndFlush(Exercise.builder().subject(Subject.DATABASE).level(Level.MEDIUM)
                .question(key).answer("다른 분야").build());
        searchProblem(key, "다른 난이도", Level.HARD);
        searchProblem("해설에만 키워드", key + " deadlock " + literal, Level.MEDIUM);
        searchProblem("기호 공통 친구 ABC", "해설", Level.MEDIUM);
        service.saveExercise(owner, oldest.getId());
        List<Exercise> matching = new ArrayList<>(List.of(oldest));
        for (int i = 0; i < 12; i++) {
            matching.add(searchProblem("교착 상태 사례 " + i, "해설", Level.MEDIUM));
        }
        Exercise hiddenInAll = matching.get(3);
        Exercise savedByOther = matching.get(5);
        for (Exercise row : matching.subList(1, matching.size())) {
            service.saveExercise(row == savedByOther ? other : owner, row.getId());
        }
        service.banExercise(owner, hiddenInAll.getId());
        var allFirst = service.getExercises(owner, Subject.OPERATING_SYSTEM, Level.MEDIUM, key, null, null);
        var allNext = service.getExercises(owner, Subject.OPERATING_SYSTEM, Level.MEDIUM,
                key, allFirst.getContent().getLast().getId(), null);
        var allBack = service.getExercises(owner, Subject.OPERATING_SYSTEM, Level.MEDIUM,
                key, null, allNext.getContent().getFirst().getId());
        assertThat(allFirst.getContent()).hasSize(9);
        assertThat(allBack.getContent().reversed()).containsExactlyElementsOf(allFirst.getContent());
        assertThat(java.util.stream.Stream.concat(allFirst.stream(), allNext.stream()).map(Exercise::getId))
                .containsExactlyElementsOf(matching.reversed().stream().filter(row -> row != hiddenInAll).map(Exercise::getId).toList());

        var savedFirst = service.getSelectedExercises(owner, Subject.OPERATING_SYSTEM, Level.MEDIUM, key, null, null);
        var savedNext = service.getSelectedExercises(owner, Subject.OPERATING_SYSTEM, Level.MEDIUM,
                key, savedFirst.getContent().getLast().getId(), null);
        assertThat(java.util.stream.Stream.concat(savedFirst.stream(), savedNext.stream()).map(Exercise::getId))
                .containsExactlyElementsOf(matching.reversed().stream().filter(row -> row != savedByOther).map(Exercise::getId).toList());
        for (String query : List.of("DEADLOCK", "%", "_", "\\", literal)) {
            assertThat(service.getExercises(owner, null, null, query, null, null)).extracting(Exercise::getId).containsExactly(oldest.getId());
            assertThat(service.getSelectedExercises(owner, null, null, query, null, null)).extracting(Exercise::getId).containsExactly(oldest.getId());
        }
    }

    private Exercise searchProblem(String question, String answer, Level level) {
        return exercises.saveAndFlush(Exercise.builder().subject(Subject.OPERATING_SYSTEM)
                .level(level).question(question).answer(answer).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "selected", "banned"})
    void cursorNavigationKeepsOrderFiltersOwnershipAndSingleQuery(String view) {
        User owner = users.saveAndFlush(new User("cursor", "hash"));
        User other = users.saveAndFlush(new User("another", "hash"));
        List<Exercise> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) rows.add(exercises.saveAndFlush(Exercise.builder()
                .subject(Subject.OPERATING_SYSTEM).level(Level.MEDIUM).question("Question " + i).answer("Answer").build()));
        exercises.saveAndFlush(Exercise.builder().subject(Subject.DATABASE).level(Level.EASY)
                .question("Other filter").answer("Answer").build());
        // Preference insertion order deliberately differs from problem order.
        for (Exercise row : rows.reversed()) {
            boolean excluded = row == rows.get(7);
            if (view.equals("selected")) service.saveExercise(excluded ? other.getId() : owner.getId(), row.getId());
            if (view.equals("banned")) service.banExercise(excluded ? other.getId() : owner.getId(), row.getId());
        }
        if (view.equals("all")) service.banExercise(owner.getId(), rows.get(7).getId());
        List<Long> expected = rows.reversed().stream().filter(row -> row != rows.get(7)).map(Exercise::getId).toList();
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();

        statistics.clear();
        Slice<Exercise> first = read(view, owner.getId(), null, null);
        assertThat(first.getContent()).extracting(Exercise::getId).containsExactlyElementsOf(expected.subList(0, 9));
        assertThat(first.hasNext()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        Slice<Exercise> second = read(view, owner.getId(), first.getContent().getLast().getId(), null);
        assertThat(second.getContent()).extracting(Exercise::getId).containsExactlyElementsOf(expected.subList(9, 18));
        Slice<Exercise> backward = read(view, owner.getId(), null, second.getContent().getFirst().getId());
        assertThat(backward.getContent().reversed()).extracting(Exercise::getId).containsExactlyElementsOf(expected.subList(0, 9));
        assertThat(backward.hasNext()).isFalse();
        Slice<Exercise> last = read(view, owner.getId(), second.getContent().getLast().getId(), null);
        assertThat(last.getContent()).extracting(Exercise::getId).containsExactlyElementsOf(expected.subList(18, 23));
        assertThat(last.hasNext()).isFalse();

        Exercise inserted = exercises.saveAndFlush(Exercise.builder().subject(Subject.OPERATING_SYSTEM)
                .level(Level.MEDIUM).question("Newly inserted").answer("Answer").build());
        if (view.equals("selected")) service.saveExercise(owner.getId(), inserted.getId());
        if (view.equals("banned")) service.banExercise(owner.getId(), inserted.getId());
        assertThat(read(view, owner.getId(), first.getContent().getLast().getId(), null).getContent())
                .extracting(Exercise::getId).containsExactlyElementsOf(expected.subList(9, 18));
        statistics.clear();
        for (Long[] cursor : new Long[][]{{0L, null}, {null, -1L}, {1L, 2L}}) {
            assertThatThrownBy(() -> read(view, owner.getId(), cursor[0], cursor[1]))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }
}
