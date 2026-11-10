package kingrangE.DCBA.service;

import jakarta.annotation.Nullable;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.BannedExerciseRepository;
import kingrangE.DCBA.repository.ExerciseRepository;
import kingrangE.DCBA.repository.SelectedExerciseRepository;
import kingrangE.DCBA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExerciseService {
    private final ExerciseRepository exerciseRepository;
    private final SelectedExerciseRepository selectedExerciseRepository;
    private final BannedExerciseRepository bannedExerciseRepository;
    private final UserRepository userRepository;

    /**
     * 조건에 따라 문제 Get
     * @param userId 유저 Id
     * @param subject 과목명 (Enum) (optional)
     * @param level 레벨 (Enum) (optional)
     * @param pageable 페이지 정보
     * @return 조건에 따라 필터링된 Exercise들의 Pages
     */
    public Page<Exercise> getExercises( Long userId,
                                       @Nullable Subject subject,
                                       @Nullable Level level,
                                        Pageable pageable) {
        return exerciseRepository.findVisibleExercises(userId, subject, level, pageable);
    }

    /**
     * User SelectedRepo에 문제 저장
     * @param userId 유저 Id
     * @param exerciseId 문제 Id
     */
    @Transactional
    public void saveExercise( Long userId, Long exerciseId) {
        // User 정보 가져옴 (없으면 Error)
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(()
                -> new jakarta.persistence.EntityNotFoundException("User not found"));

        // Exercise 정보 가져옴 (없으면 Error)
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Exercise not found"));

        // 선택된 운동을 관리하는 Repo에 이미 저장되어 있는지 확인 (있으면 중복 저장 X)
        if (selectedExerciseRepository.findByUserAndExercise(user, exercise).isPresent()) {
            return; // Already saved
        }

        // 선택된 운동이 없다면, 객체 생성 후 저장
        SelectedExercise selectedExercise = SelectedExercise.builder()
                .user(user)
                .exercise(exercise)
                .build();
        selectedExerciseRepository.save(selectedExercise);
    }

    /**
     * User SelectedRepo 저장 취소
     * @param userId 유저 Id
     * @param exerciseId 문제 Id
     */
    @Transactional
    public void cancelSaveExercise(Long userId, Long exerciseId) {
        // user와 운동 정보 가져오기
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(()
                -> new jakarta.persistence.EntityNotFoundException("User not found"));
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Exercise not found"));

        // user와 운동 정보를 기반으로 선택된 문제들을 찾아서 있으면 삭제하도록 함.(메서드 참조 이용)
        selectedExerciseRepository.findByUserAndExercise(user, exercise)
                .ifPresent(selectedExerciseRepository::delete);
    }

    /**
     * User BannedRepo 저장 (문제 Pool에서 안 보이도록 설정)
     * @param userId 유저 Id
     * @param exerciseId 문제 Id
     */
    @Transactional
    public void banExercise(Long userId, Long exerciseId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(()
                -> new jakarta.persistence.EntityNotFoundException("User not found"));
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Exercise not found"));

        if (bannedExerciseRepository.findByUserAndExercise(user, exercise).isPresent()) {
            return; // Already banned
        }

        BannedExercise bannedExercise = new BannedExercise(null, user, exercise);
        bannedExerciseRepository.save(bannedExercise);
    }

    /**
     * User BannedRepo 저장 취소 (문제 Pool에서 다시 보이도록 설정)
     * @param userId 유저 Id
     * @param exerciseId 문제 Id
     */
    @Transactional
    public void cancelBanExercise(Long userId, Long exerciseId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(()
                -> new jakarta.persistence.EntityNotFoundException("User not found"));
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Exercise not found"));

        bannedExerciseRepository.findByUserAndExercise(user, exercise)
                .ifPresent(bannedExerciseRepository::delete);
    }

    /**
     * User SelectedRepo에 저장된 문제 Page 가져오기
     * @param userId User Id
     * @param pageable Page 정보
     * @return 유저가 저장한 문제들의 Pages
     */
    public Page<Exercise> getSelectedExercises(Long userId, Subject subject, Level level, Pageable pageable) {
        return selectedExerciseRepository.findExercises(userId, subject, level, pageable);
    }

    /**
     * User BannedRepo에 저장된 문제 Page 가져오기
     * @param userId 유저 Id
     * @param pageable Page 정보
     * @return 유저가 Ban한 문제들의 Pages
     */
    public Page<Exercise> getBannedExercises(Long userId, Subject subject, Level level, Pageable pageable) {
        return bannedExerciseRepository.findExercises(userId, subject, level, pageable);
    }

    public List<Long> getSavedExerciseIds(Long userId, List<Long> exerciseIds) {
        return exerciseIds.isEmpty() ? List.of() : selectedExerciseRepository.findExerciseIds(userId, exerciseIds);
    }

    public List<Long> getBannedExerciseIds(Long userId, List<Long> exerciseIds) {
        return exerciseIds.isEmpty() ? List.of() : bannedExerciseRepository.findExerciseIds(userId, exerciseIds);
    }
}
