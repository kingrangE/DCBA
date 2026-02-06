package kingrangE.DCBA.service;

import jakarta.annotation.Nullable;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.repository.BannedExerciseRepository;
import kingrangE.DCBA.repository.ExerciseRepository;
import kingrangE.DCBA.repository.SelectedExerciseRepository;
import kingrangE.DCBA.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
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
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        List<Long> bannedIds = bannedExerciseRepository.findAllByUser(user, Pageable.unpaged())
                .stream()
                .map(b -> b.getExercise().getId())
                .toList();

        if (bannedIds.isEmpty()) { // 밴한 문제가 없다면
            if (subject != null && level != null) { // 필터링 둘 다 존재
                return exerciseRepository.findBySubjectAndLevel(subject, level, pageable); // 필터링한 결과 반환
            } else if (subject != null) { // 과목만 필터링
                return exerciseRepository.findBySubject(subject, pageable); // 과목 필터링 결과 반환
            } else if (level != null) { // 수준만 필터링
                return exerciseRepository.findByLevel(level, pageable); // 수준 필터링 결과 반환
            } else { // 필터링 없음
                return exerciseRepository.findAll(pageable); // 전체 문제 반환
            }
        }

        //밴한 문제가 있는 경우 (method 제외 로직은 같음)(method만 banned 문제 제외하는걸로 이용)
        if (subject != null && level != null) {
            return exerciseRepository.findBySubjectAndLevelAndIdNotIn(subject, level, bannedIds, pageable);
        } else if (subject != null) {
            return exerciseRepository.findBySubjectAndIdNotIn(subject, bannedIds, pageable);
        } else if (level != null) {
            return exerciseRepository.findByLevelAndIdNotIn(level, bannedIds, pageable);
        } else {
            return exerciseRepository.findByIdNotIn(bannedIds, pageable);
        }
    }

    /**
     * User SelectedRepo에 문제 저장
     * @param userId 유저 Id
     * @param exerciseId 문제 Id
     */
    @Transactional
    public void saveExercise( Long userId, Long exerciseId) {
        // User 정보 가져옴 (없으면 Error)
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));

        // Exercise 정보 가져옴 (없으면 Error)
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new IllegalArgumentException("Exercise not found"));

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
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new IllegalArgumentException("Exercise not found"));

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
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new IllegalArgumentException("Exercise not found"));

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
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));
        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new IllegalArgumentException("Exercise not found"));

        bannedExerciseRepository.findByUserAndExercise(user, exercise)
                .ifPresent(bannedExerciseRepository::delete);
    }

    /**
     * User SelectedRepo에 저장된 문제 Page 가져오기
     * @param userId User Id
     * @param pageable Page 정보
     * @return 유저가 저장한 문제들의 Pages
     */
    public Page<Exercise> getSelectedExercises(Long userId, Pageable pageable) {
        // UserId를 기반으로 user정보 Get
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));

        // User가 선택한 문제 목록을 가져오도록 함.
        Page<SelectedExercise> selectedPage = selectedExerciseRepository.findAllByUser(user, pageable);
        //메서드 참조를 이용, Page에 들어있는 SelectedExercise 각 객체에서 getExercise(return Exercise)를 수행하도록 함.
        return selectedPage.map(SelectedExercise::getExercise);
    }

    /**
     * User BannedRepo에 저장된 문제 Page 가져오기
     * @param userId 유저 Id
     * @param pageable Page 정보
     * @return 유저가 Ban한 문제들의 Pages
     */
    public Page<Exercise> getBannedExercises(Long userId, Pageable pageable) {
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));
        Page<BannedExercise> bannedPage = bannedExerciseRepository.findAllByUser(user, pageable);
        return bannedPage.map(BannedExercise::getExercise);
    }

    /**
     * User Selected Repo에 저장된 문제 Id들 가져오기
     * @param userId 유저 Id
     * @return User Selected Repo에 있는 문제 Id List
     */
    public List<Long> getSavedExerciseIds(Long userId) {
        // User 정보 가져오기
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));

        // User가 선택한 문제들 가져오도록 함.
        return selectedExerciseRepository.findAllByUser(user, Pageable.unpaged())//Page처리 하지말고 전체를 다 읽음
                .stream()
                .map(e -> e.getExercise().getId())
                .toList();
    }

    /**
     * User Banned Repo에 저장된 문제 Id들 가져오기
     * @param userId 유저 Id
     * @return User Banned Repo에 있는 문제 Id List
     */
    public List<Long> getBannedExerciseIds(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(()
                -> new IllegalArgumentException("User not found"));
        return bannedExerciseRepository.findAllByUser(user, Pageable.unpaged())
                .stream()
                .map(e -> e.getExercise().getId())
                .toList();
    }
}
