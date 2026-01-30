package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.repository.ExerciseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExerciseService {
    private final ExerciseRepository exerciseRepository;

    @Autowired
    public ExerciseService(ExerciseRepository exerciseRepository) {
        this.exerciseRepository = exerciseRepository;
    }

    public Exercise generateExercise() {
        // 문제 생성 및 저장
        return null;
    }

    private Boolean validateExerciseDuplicate(){
        // 문제 중복 여부 검사
        return null;
    }

    public List<Boolean> getAllExercise(){
        // 모든 문제 Get
        return null;
    }

    public Exercise getExercise(Subject subject,Level level){
        // 유저에게 맞는 문제 하나 반환
        return null;
    }
}
