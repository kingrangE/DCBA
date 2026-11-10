package kingrangE.DCBA.controller;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpSession;
import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.api.ExerciseResponse;
import kingrangE.DCBA.dto.api.GenerationRequest;
import kingrangE.DCBA.dto.api.GenerationResponse;
import kingrangE.DCBA.dto.api.PageResponse;
import kingrangE.DCBA.exception.UnauthorizedException;
import kingrangE.DCBA.service.ExerciseGenerationQueueService;
import kingrangE.DCBA.service.ExerciseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/exercises")
@RequiredArgsConstructor
public class ExerciseController {

    private final ExerciseService exerciseService;
    private final ExerciseGenerationQueueService exerciseGenerationQueueService;

    @GetMapping
    public PageResponse<ExerciseResponse> exercises(
            @RequestParam(defaultValue = "all") String view,
            @RequestParam(required = false) Subject subject,
            @RequestParam(required = false) Level level,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            HttpSession session) {
        User user = requireUser(session);
        Slice<Exercise> slice = switch (view) {
            case "all" -> exerciseService.getExercises(user.getId(), subject, level, before, after);
            case "selected" -> exerciseService.getSelectedExercises(user.getId(), subject, level, before, after);
            case "banned" -> exerciseService.getBannedExercises(user.getId(), subject, level, before, after);
            default -> throw new IllegalArgumentException("지원하지 않는 문제 보기 방식입니다.");
        };
        List<Exercise> content = after == null ? slice.getContent() : slice.getContent().reversed();
        List<Long> ids = content.stream().map(Exercise::getId).toList();
        Set<Long> savedIds = view.equals("selected") ? Set.copyOf(ids)
                : Set.copyOf(exerciseService.getSavedExerciseIds(user.getId(), ids));
        Set<Long> bannedIds = view.equals("all") ? Set.of() : view.equals("banned") ? Set.copyOf(ids)
                : Set.copyOf(exerciseService.getBannedExerciseIds(user.getId(), ids));
        return new PageResponse<>(content.stream().map(e -> ExerciseResponse.from(e, savedIds, bannedIds)).toList(),
                after == null ? before != null : slice.hasNext(), after != null || slice.hasNext(),
                content.isEmpty() ? null : ids.getFirst(), content.isEmpty() ? null : ids.getLast());
    }

    @GetMapping("/options")
    public Map<String, List<Map<String, Object>>> options(HttpSession session) {
        requireUser(session);

        List<Map<String, Object>> subjects = Arrays.stream(Subject.values())
                .map(subject -> option(subject.name(), subject.getSubjectName()))
                .toList();
        List<Map<String, Object>> levels = Arrays.stream(Level.values())
                .map(level -> levelOption(level.name(), level.getPromptName(), level.getLevel()))
                .toList();
        return Map.of("subjects", subjects, "levels", levels);
    }

    @PostMapping("/{exerciseId}/saved")
    public ResponseEntity<Void> save(@PathVariable Long exerciseId, HttpSession session) {
        exerciseService.saveExercise(requireUser(session).getId(), exerciseId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{exerciseId}/saved")
    public ResponseEntity<Void> cancelSave(@PathVariable Long exerciseId, HttpSession session) {
        exerciseService.cancelSaveExercise(requireUser(session).getId(), exerciseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{exerciseId}/banned")
    public ResponseEntity<Void> ban(@PathVariable Long exerciseId, HttpSession session) {
        exerciseService.banExercise(requireUser(session).getId(), exerciseId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{exerciseId}/banned")
    public ResponseEntity<Void> cancelBan(@PathVariable Long exerciseId, HttpSession session) {
        exerciseService.cancelBanExercise(requireUser(session).getId(), exerciseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generation-requests")
    public ResponseEntity<GenerationResponse> requestGeneration(
            @RequestBody GenerationRequest request,
            HttpSession session) {
        requireUser(session);
        if (request.subject() == null || request.level() == null) {
            throw new IllegalArgumentException("분야와 난이도를 모두 선택해 주세요.");
        }

        try {
            Long queueSize = exerciseGenerationQueueService.enqueue(request.subject(), request.level());
            long safeQueueSize = queueSize == null ? 0 : queueSize;
            String message = "%s · Level %d 문제 생성 요청이 등록되었습니다."
                    .formatted(request.subject().getSubjectName(), request.level().getLevel());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new GenerationResponse(message, safeQueueSize));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "문제 생성 요청을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    exception);
        }
    }

    private User requireUser(HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        return user;
    }

    private Map<String, Object> option(String value, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }

    private Map<String, Object> levelOption(String value, String label, int level) {
        Map<String, Object> option = option(value, label);
        option.put("level", level);
        return option;
    }
}
