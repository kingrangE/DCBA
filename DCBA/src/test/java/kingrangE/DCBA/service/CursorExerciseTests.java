package kingrangE.DCBA.service;

import jakarta.persistence.EntityManager;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
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
            case "selected" -> service.getSelectedExercises(userId, Subject.OPERATING_SYSTEM, Level.MEDIUM, before, after);
            case "banned" -> service.getBannedExercises(userId, Subject.OPERATING_SYSTEM, Level.MEDIUM, before, after);
            default -> service.getExercises(userId, Subject.OPERATING_SYSTEM, Level.MEDIUM, before, after);
        };
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
