package kingrangE.DCBA.dto.api;

import java.time.LocalDateTime;
import java.util.Set;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;

public record ExerciseResponse(
        Long id,
        String question,
        String answer,
        Subject subject,
        String subjectName,
        Level level,
        int levelNumber,
        LocalDateTime createdAt,
        boolean saved,
        boolean banned) {

    public static ExerciseResponse from(Exercise exercise, Set<Long> savedIds, Set<Long> bannedIds) {
        return new ExerciseResponse(
                exercise.getId(),
                exercise.getQuestion(),
                exercise.getAnswer(),
                exercise.getSubject(),
                exercise.getSubject().getSubjectName(),
                exercise.getLevel(),
                exercise.getLevel().getLevel(),
                exercise.getCreatedAt(),
                savedIds.contains(exercise.getId()),
                bannedIds.contains(exercise.getId()));
    }
}
