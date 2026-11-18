package kingrangE.DCBA.service;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "REVIEW_FEEDBACK_ENABLED=true")
@ActiveProfiles("local")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReviewService.class, ExerciseService.class})
class ReviewDataTests {
    static final String RUBRIC = """
            [{"id":"definition","label":"정의","description":"개념을 설명합니다."},
             {"id":"example","label":"예시","description":"예시를 제시합니다."}]
            """;

    @Autowired UserRepository users;
    @Autowired ExerciseRepository pool;
    @Autowired ExerciseService exercises;
    @Autowired ReviewService reviews;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ReviewFeedbackService feedback;

    @Test
    void samplesOnlyOwnedSavedUnblockedItemsWithCurrentFilters() {
        Long user = users.saveAndFlush(new User("reviewer", "hash")).getId();
        Long other = users.saveAndFlush(new User("review2", "hash")).getId();
        assertThat(reviews.sample(user, "random", null, null).items()).isEmpty();
        Exercise first = problem(Subject.DATABASE, Level.EASY);
        Exercise second = problem(Subject.DATABASE, Level.HARD);
        Exercise third = problem(Subject.OPERATING_SYSTEM, Level.EASY);
        Exercise fourth = problem(Subject.OPERATING_SYSTEM, Level.HARD);
        Exercise outside = problem(Subject.DATABASE, Level.EASY);
        exercises.saveExercise(other, outside.getId());
        exercises.saveExercise(user, first.getId());
        jdbc.update("INSERT INTO exercise_feedback_rubric (exercise_id, criteria_json) VALUES (?, ?)", first.getId(), RUBRIC);
        assertThat(reviews.sample(user, "random", null, null).items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(first.getId());
            assertThat(item.reviewAgain()).isFalse();
            assertThat(item.feedbackAvailable()).isTrue();
        });

        exercises.saveExercise(user, second.getId());
        assertThat(reviews.sample(user, "random", Subject.DATABASE, null).items())
                .extracting(ReviewService.Item::id).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(reviews.sample(user, "random", Subject.DATABASE, Level.HARD).items()).singleElement()
                .satisfies(item -> assertThat(item.feedbackAvailable()).isFalse());
        exercises.saveExercise(user, third.getId());
        exercises.saveExercise(user, fourth.getId());
        assertThat(reviews.sample(user, "random", null, null).items()).hasSize(3)
                .extracting(ReviewService.Item::id).doesNotHaveDuplicates().doesNotContain(outside.getId());

        reviews.mark(user, first.getId(), true);
        reviews.mark(user, first.getId(), true);
        assertThat(reviews.sample(user, "marked", null, null).items()).extracting(ReviewService.Item::id)
                .containsExactly(first.getId());
        exercises.banExercise(user, first.getId());
        assertThat(reviews.sample(user, "marked", null, null).items()).isEmpty();
        assertThatThrownBy(() -> reviews.mark(user, first.getId(), false)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> reviews.feedback(user, first.getId(), "answer")).isInstanceOf(EntityNotFoundException.class);
        exercises.cancelBanExercise(user, first.getId());
        assertThat(reviews.sample(user, "marked", null, null).items()).hasSize(1);

        assertThatThrownBy(() -> reviews.feedback(other, first.getId(), "answer")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> reviews.feedback(user, second.getId(), "answer"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(feedback);
        var result = new ReviewProtocol.Feedback(List.of(), "다음 단계");
        when(feedback.evaluate(eq(user), any())).thenReturn(result);
        assertThat(reviews.feedback(user, first.getId(), "answer")).isEqualTo(result);

        reviews.mark(user, first.getId(), false);
        assertThat(reviews.sample(user, "marked", null, null).items()).isEmpty();
        reviews.mark(user, first.getId(), true);
        exercises.cancelSaveExercise(user, first.getId());
        assertThatThrownBy(() -> reviews.feedback(user, first.getId(), "answer")).isInstanceOf(EntityNotFoundException.class);
        exercises.saveExercise(user, first.getId());
        assertThat(reviews.sample(user, "marked", null, null).items()).isEmpty();
    }

    private Exercise problem(Subject subject, Level level) {
        return pool.saveAndFlush(Exercise.builder().subject(subject).level(level)
                .question("Question").answer("Model answer").build());
    }
}
