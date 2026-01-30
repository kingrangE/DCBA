package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MemoryExerciseRepository implements  ExerciseRepository{
    ConcurrentHashMap<Integer,Exercise> repo = new ConcurrentHashMap<>();
    Integer id = 0;

    @Override
    public Integer save(Exercise exercise) {
        exercise.setNum(++id);
        repo.put(exercise.getNum(),exercise);
        return exercise.getNum();
    }

    @Override
    public Optional<Exercise> findByExerciseNumber(Integer exerciseNumber) {
        return Optional.ofNullable(repo.get(exerciseNumber));
    }

    @Override
    public List<Exercise> findAllExercises() {
        return new ArrayList<>(repo.values());
    }

    @Override
    public List<Exercise> findExercisesBySubject(Subject subject) {
        return repo.values().stream()
                .filter(exercise -> exercise.getSubject() == subject)
                .toList();
    }

    @Override
    public List<Exercise> findExercisesByLevel(Level level) {
        return repo.values().stream()
                .filter(exercise -> exercise.getLevel() == level)
                .toList();
    }

    @Override
    public List<Exercise> findExercisesBySubjectAndLevel(Subject subject, Level level) {
        return repo.values().stream()
                .filter(exercise -> exercise.getSubject() == subject
                        && exercise.getLevel()==level) //ENUM 타입이므로 == 비교
                .toList();
    }

}
