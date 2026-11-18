package kingrangE.DCBA.service;

import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.repository.SelectedExerciseRepository;
import kingrangE.DCBA.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
    private final SelectedExerciseRepository selected;
    private final UserRepository users;
    private final ReviewFeedbackService feedback;
    private final boolean feedbackEnabled;

    public ReviewService(SelectedExerciseRepository selected, UserRepository users, ReviewFeedbackService feedback,
                         @Value("${REVIEW_FEEDBACK_ENABLED:false}") boolean feedbackEnabled) {
        this.selected = selected;
        this.users = users;
        this.feedback = feedback;
        this.feedbackEnabled = feedbackEnabled;
    }

    public record Item(Long id, String question, String answer, Subject subject, String subjectName, Level level,
                       int levelNumber, LocalDateTime createdAt, boolean saved, boolean banned,
                       boolean reviewAgain, boolean feedbackAvailable) {}
    public record Sample(List<Item> items) {}

    public Sample sample(Long userId, String mode, Subject subject, Level level) {
        boolean marked = switch (mode) {
            case "random" -> false;
            case "marked" -> true;
            default -> throw new IllegalArgumentException("지원하지 않는 복습 방식입니다.");
        };
        return new Sample(selected.sampleReview(userId, subject == null ? null : subject.name(),
                level == null ? null : level.name(), marked).stream().map(row -> {
            var rowSubject = Subject.valueOf(row.getSubject());
            var rowLevel = Level.valueOf(row.getLevel());
            return new Item(row.getId(), row.getQuestion(), row.getAnswer(), rowSubject, rowSubject.getSubjectName(),
                    rowLevel, rowLevel.getLevel(), row.getCreatedAt(), true, false, row.getReviewAgain(),
                    feedbackEnabled && !ReviewProtocol.rubric(row.getCriteriaJson()).isEmpty());
        }).toList());
    }

    @Transactional
    public void mark(Long userId, Long exerciseId, boolean enabled) {
        users.findByIdForUpdate(userId).orElseThrow(EntityNotFoundException::new);
        if (selected.setReviewAgain(userId, exerciseId, enabled) == 0
                && selected.findReview(userId, exerciseId).isEmpty()) {
            throw new EntityNotFoundException();
        }
    }

    public ReviewProtocol.Feedback feedback(Long userId, Long exerciseId, String answer) {
        var row = selected.findReview(userId, exerciseId).orElseThrow(EntityNotFoundException::new);
        var rubric = feedbackEnabled ? ReviewProtocol.rubric(row.getCriteriaJson()) : List.<ReviewProtocol.Criterion>of();
        if (rubric.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 문제에 AI 피드백을 사용할 수 없어요.");
        }
        return feedback.evaluate(userId, new ReviewProtocol.Request(row.getQuestion(), row.getLevel(), row.getAnswer(), rubric, answer));
    }
}
