package kingrangE.DCBA.service;

import jakarta.annotation.Nullable;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.BannedExerciseRepository;
import kingrangE.DCBA.repository.ExerciseRepository;
import kingrangE.DCBA.repository.SelectedExerciseRepository;
import kingrangE.DCBA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
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
     * @param before 이 식별자보다 오래된 문제
     * @return 조건에 맞는 문제와 다음 구간 존재 여부
     */
    public Slice<Exercise> getExercises( Long userId,
                                       @Nullable Subject subject,
                                       @Nullable Level level,
                                        Long before, Long after) {
        return exerciseRepository.findVisibleExercises(userId, subject, level, before, after, cursorPage(before, after));
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
        userRepository.findByIdForUpdate(userId).orElseThrow(()
                -> new jakarta.persistence.EntityNotFoundException("User not found"));
        if (!exerciseRepository.existsById(exerciseId)) {
            throw new jakarta.persistence.EntityNotFoundException("Exercise not found");
        }
        selectedExerciseRepository.deletePreference(userId, exerciseId);
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
        userRepository.findByIdForUpdate(userId).orElseThrow(()
                -> new jakarta.persistence.EntityNotFoundException("User not found"));
        if (!exerciseRepository.existsById(exerciseId)) {
            throw new jakarta.persistence.EntityNotFoundException("Exercise not found");
        }
        bannedExerciseRepository.deletePreference(userId, exerciseId);
    }

    /**
     * User SelectedRepo에 저장된 문제 Page 가져오기
     * @param userId User Id
     * @param before 이 식별자보다 오래된 문제
     * @return 저장한 문제와 다음 구간 존재 여부
     */
    public Slice<Exercise> getSelectedExercises(Long userId, Subject subject, Level level, Long before, Long after) {
        return selectedExerciseRepository.findExercises(userId, subject, level, before, after, cursorPage(before, after));
    }

    /**
     * User BannedRepo에 저장된 문제 Page 가져오기
     * @param userId 유저 Id
     * @param before 이 식별자보다 오래된 문제
     * @return 차단한 문제와 다음 구간 존재 여부
     */
    public Slice<Exercise> getBannedExercises(Long userId, Subject subject, Level level, Long before, Long after) {
        return bannedExerciseRepository.findExercises(userId, subject, level, before, after, cursorPage(before, after));
    }

    private Pageable cursorPage(Long before, Long after) {
        if ((before != null && after != null) || (before != null && before <= 0) || (after != null && after <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return PageRequest.of(0, 9, Sort.by(after == null ? Sort.Direction.DESC : Sort.Direction.ASC, "e.id"));
    }

    public List<Long> getSavedExerciseIds(Long userId, List<Long> exerciseIds) {
        return exerciseIds.isEmpty() ? List.of() : selectedExerciseRepository.findExerciseIds(userId, exerciseIds);
    }

    public List<Long> getBannedExerciseIds(Long userId, List<Long> exerciseIds) {
        return exerciseIds.isEmpty() ? List.of() : bannedExerciseRepository.findExerciseIds(userId, exerciseIds);
    }
}
