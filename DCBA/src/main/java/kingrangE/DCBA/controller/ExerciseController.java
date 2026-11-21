package kingrangE.DCBA.controller;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.text.Normalizer;
import java.util.regex.Pattern;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/exercises")
@RequiredArgsConstructor
public class ExerciseController {
    private static final Pattern PRIVATE_INTENT = Pattern.compile(
            "(?iuU)[\\w.+-]+\\s*@\\s*[\\w-]+(?:\\s*\\.\\s*[\\w-]+)+"
            + "|(?<![0-9])(?:\\+?82[\\s.-]*)?0?1[016789][\\s.()‐‑–—-]*[0-9]{3,4}[\\s.()‐‑–—-]*[0-9]{4}(?![0-9])"
            + "|\\b(?:qa|test|demo|acct|account|user|tenant|org|employee|customer|cust|private|corp|staff|id)[\\W_]{0,8}[0-9]{1,12}\\b"
            + "|(?:계정|사용자|사번|식별자|조직|고객|user|account|employee|tenant|org)\\s*(?:id|번호|코드)?\\s*[:=]\\s*[a-z0-9][a-z0-9_.@+/-]+"
            + "|\\b[0-9a-f]{8}(?:[\\s‐‑–—-][0-9a-f]{4}){3}[\\s‐‑–—-][0-9a-f]{12}\\b"
            + "|\\b[\\w-]+(?:\\.[\\w-]+)*\\.(?:internal|corp|local)\\b"
            + "|(?<![a-z0-9])(?!(?:aes|ascii|http|ieee|iso|md|rfc|sha|tls|utf)[\\W_]{0,8}[0-9])"
            + "(?!(?:postgresql[\\s._·‐‑–—*-]{1,8}5432|redis[\\s._·‐‑–—*-]{1,8}6379"
            + "|mariadb[\\s._·‐‑–—*-]{1,8}3306)(?![a-z0-9]))"
            + "[a-z]{2,24}(?:[0-9]{3,12}|\\s{1,8}[0-9]{5,12}"
            + "|[\\s._·‐‑–—*-]{0,7}[._·‐‑–—*-][\\s._·‐‑–—*-]{0,7}[0-9]{3,12})(?![a-z0-9])");

    private final ExerciseService exerciseService;
    private final ExerciseGenerationQueueService exerciseGenerationQueueService;

    @GetMapping
    public PageResponse<ExerciseResponse> exercises(
            @RequestParam(defaultValue = "all") String view,
            @RequestParam(required = false) Subject subject,
            @RequestParam(required = false) Level level,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            HttpSession session) {
        User user = requireUser(session);
        String keyword = q == null ? "" : trimSearchKeyword(q);
        if (keyword.length() > 100) {
            throw new IllegalArgumentException("검색어는 100자 이하로 입력해 주세요.");
        }
        if (view.equals("banned") && !keyword.isEmpty()) {
            throw new IllegalArgumentException("차단한 문제에서는 검색을 사용할 수 없어요.");
        }
        String search = keyword.isEmpty() ? null : keyword;
        Slice<Exercise> slice = switch (view) {
            case "all" -> exerciseService.getExercises(user.getId(), subject, level, search, before, after);
            case "selected" -> exerciseService.getSelectedExercises(user.getId(), subject, level, search, before, after);
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

    private static String trimSearchKeyword(String keyword) {
        int start = 0;
        int end = keyword.length();
        while (start < end && isSearchWhitespace(keyword.charAt(start))) start++;
        while (end > start && isSearchWhitespace(keyword.charAt(end - 1))) end--;
        return keyword.substring(start, end);
    }

    // Match JavaScript String.trim(), including non-breaking spaces and the BOM.
    private static boolean isSearchWhitespace(char character) {
        return Character.isSpaceChar(character) || (character >= '\t' && character <= '\r') || character == '\uFEFF';
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
        User user = requireUser(session);
        if (request.subject() == null || request.level() == null) {
            throw new IllegalArgumentException("분야와 난이도를 모두 선택해 주세요.");
        }
        String intent = request.learningIntent() == null ? "" : trimSearchKeyword(request.learningIntent());
        if (intent.codePointCount(0, intent.length()) > 200) {
            throw new IllegalArgumentException("학습하고 싶은 내용은 200자 이하로 입력해 주세요.");
        }
        String normalizedIntent = Normalizer.normalize(intent, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "");
        if (PRIVATE_INTENT.matcher(normalizedIntent).find()) {
            throw new IllegalArgumentException("계정, 이메일, 조직 식별정보를 빼고 공개 가능한 CS 개념이나 학습 목표를 적어 주세요.");
        }

        try {
            var accepted = exerciseGenerationQueueService.enqueue(request.subject(), request.level(), user.getId(),
                    intent.isEmpty() ? null : intent);
            String message = "%s · Level %d 문제 생성 요청이 등록되었습니다."
                    .formatted(request.subject().getSubjectName(), request.level().getLevel());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new GenerationResponse(message, accepted.queueSize(), accepted.requestId()));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "문제 생성 요청을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    exception);
        }
    }

    @GetMapping("/generation-requests/{requestId}")
    public ExerciseGenerationQueueService.Status generationStatus(@PathVariable UUID requestId, HttpSession session) {
        return exerciseGenerationQueueService.status(requestId, requireUser(session).getId());
    }

    @GetMapping("/generation-requests/notifications")
    public ExerciseGenerationQueueService.Notifications generationNotifications(
            @RequestParam(defaultValue = "0") int page, HttpSession session) {
        return exerciseGenerationQueueService.notifications(requireUser(session).getId(), page);
    }

    @GetMapping("/generation-requests/recent")
    public List<ExerciseGenerationQueueService.RecentRequest> recentGenerationRequests(HttpSession session) {
        return exerciseGenerationQueueService.recent(requireUser(session).getId());
    }

    @PutMapping("/generation-requests/{requestId}/read")
    public ResponseEntity<Void> markGenerationRead(@PathVariable UUID requestId, HttpSession session) {
        exerciseGenerationQueueService.markRead(requestId, requireUser(session).getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/generation-requests/{requestId}/exercise")
    public ExerciseResponse generatedExercise(@PathVariable UUID requestId, HttpSession session) {
        Long userId = requireUser(session).getId();
        Long exerciseId = exerciseGenerationQueueService.completedExercise(requestId, userId);
        if (exerciseId == null || !exerciseService.getBannedExerciseIds(userId, List.of(exerciseId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문제를 열 수 없어요.");
        }
        Exercise exercise = exerciseService.findExercise(exerciseId);
        return ExerciseResponse.from(exercise, Set.copyOf(exerciseService.getSavedExerciseIds(userId, List.of(exerciseId))), Set.of());
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
