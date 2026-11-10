package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrent;MODE=MariaDB;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ConcurrentExerciseTests {
    @Autowired ExerciseService service;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository users;
    @Autowired ExerciseRepository exercises;
    @Autowired SelectedExerciseRepository selected;
    @Autowired BannedExerciseRepository banned;

    @Test
    void concurrentRequestsKeepOnePreferenceAndCanCancel() throws Exception {
        User user = users.saveAndFlush(new User("concurrent", "test"));
        Exercise exercise = exercises.saveAndFlush(Exercise.builder().subject(Subject.ALGORITHM)
                .level(Level.EASY).question("Question").answer("Answer").build());
        runTogether(() -> {
            service.saveExercise(user.getId(), exercise.getId());
            service.banExercise(user.getId(), exercise.getId());
        });
        assertThat(selected.findAllByUser(user, Pageable.unpaged()).getTotalElements()).isEqualTo(1);
        assertThat(banned.findAllByUser(user, Pageable.unpaged()).getTotalElements()).isEqualTo(1);
        assertThat(service.getExercises(user.getId(), null, null, Pageable.ofSize(9)).getContent())
                .extracting(Exercise::getId).doesNotContain(exercise.getId());
        assertThat(service.getSelectedExercises(user.getId(), Subject.ALGORITHM, Level.EASY, Pageable.ofSize(9)).getContent()).hasSize(1);
        assertThat(service.getSelectedExercises(user.getId(), Subject.DATABASE, null, Pageable.ofSize(9)).getContent()).isEmpty();
        assertThat(service.getSavedExerciseIds(user.getId(), java.util.List.of(exercise.getId()))).containsExactly(exercise.getId());
        assertThat(service.getBannedExerciseIds(user.getId(), java.util.List.of())).isEmpty();
        runTogether(() -> {
            service.cancelSaveExercise(user.getId(), exercise.getId());
            service.cancelBanExercise(user.getId(), exercise.getId());
        });
        assertThat(selected.findAllByUser(user, Pageable.unpaged()).getTotalElements()).isZero();
        assertThat(banned.findAllByUser(user, Pageable.unpaged()).getTotalElements()).isZero();
    }

    @Test
    void cancellationUsesThreeStatementsAndKeepsOtherUsersPreferences() {
        User owner = users.saveAndFlush(new User("owner", "test"));
        User other = users.saveAndFlush(new User("other", "test"));
        Exercise exercise = exercises.saveAndFlush(Exercise.builder().subject(Subject.ALGORITHM)
                .level(Level.EASY).question("Question").answer("Answer").build());
        for (User user : java.util.List.of(owner, other)) {
            service.saveExercise(user.getId(), exercise.getId());
            service.banExercise(user.getId(), exercise.getId());
        }
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        service.cancelSaveExercise(owner.getId(), exercise.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(1);
        statistics.clear();
        service.cancelBanExercise(owner.getId(), exercise.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(1);
        service.cancelSaveExercise(owner.getId(), exercise.getId());
        service.cancelBanExercise(owner.getId(), exercise.getId());
        assertThat(service.getSelectedExercises(owner.getId(), null, null, Pageable.ofSize(9))).isEmpty();
        assertThat(service.getBannedExercises(owner.getId(), null, null, Pageable.ofSize(9))).isEmpty();
        assertThat(service.getSelectedExercises(other.getId(), null, null, Pageable.ofSize(9))).hasSize(1);
        assertThat(service.getBannedExercises(other.getId(), null, null, Pageable.ofSize(9))).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelSaveExercise(owner.getId(), Long.MAX_VALUE))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelBanExercise(Long.MAX_VALUE, exercise.getId()))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    @Test
    void eachListLoadsOneSliceWithoutASeparateCountQuery() {
        User owner = users.saveAndFlush(new User("sliceOwner", "test"));
        User reader = users.saveAndFlush(new User("sliceRead", "test"));
        for (int i = 0; i < 12; i++) {
            Exercise exercise = exercises.saveAndFlush(Exercise.builder().subject(Subject.DATABASE)
                    .level(Level.HARD).question("Question " + i).answer("Answer").build());
            service.saveExercise(owner.getId(), exercise.getId());
            service.banExercise(owner.getId(), exercise.getId());
        }
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        for (String view : java.util.List.of("all", "selected", "banned")) {
            statistics.clear();
            var slice = switch (view) {
                case "selected" -> service.getSelectedExercises(owner.getId(), Subject.DATABASE, Level.HARD, Pageable.ofSize(9));
                case "banned" -> service.getBannedExercises(owner.getId(), Subject.DATABASE, Level.HARD, Pageable.ofSize(9));
                default -> service.getExercises(reader.getId(), Subject.DATABASE, Level.HARD, Pageable.ofSize(9));
            };
            assertThat(slice.getContent()).hasSize(9);
            assertThat(slice.hasNext()).isTrue();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        }
    }

    private void runTogether(Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> { start.await(); action.run(); return null; }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
        }
    }
}
