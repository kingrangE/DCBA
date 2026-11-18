package kingrangE.DCBA.controller;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.api.ExerciseResponse;
import kingrangE.DCBA.dto.api.PageResponse;
import kingrangE.DCBA.exception.UnauthorizedException;
import kingrangE.DCBA.service.ExerciseService;
import kingrangE.DCBA.service.GenerationAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final GenerationAdminService generationAdmin;
    private final ExerciseService exercises;

    @GetMapping("/automatic-generation/pause")
    public Map<String, Boolean> status(HttpSession session) {
        requireAdmin(session);
        return Map.of("paused", generationAdmin.isPaused());
    }

    @PutMapping("/automatic-generation/pause")
    public Map<String, Boolean> pause(HttpSession session) {
        requireAdmin(session);
        generationAdmin.pause();
        return Map.of("paused", true);
    }

    @GetMapping("/exercises")
    public PageResponse<ExerciseResponse> exercises(
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            HttpSession session) {
        requireAdmin(session);
        var slice = exercises.getExercisePool(before, after);
        List<Exercise> content = after == null ? slice.getContent() : slice.getContent().reversed();
        return new PageResponse<>(content.stream().map(exercise -> ExerciseResponse.from(exercise, Set.of(), Set.of())).toList(),
                after == null ? before != null : slice.hasNext(), after != null || slice.hasNext(),
                content.isEmpty() ? null : content.getFirst().getId(),
                content.isEmpty() ? null : content.getLast().getId());
    }

    @DeleteMapping("/exercises/{id}")
    public ResponseEntity<Void> deleteExercise(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        exercises.deleteExerciseFromPool(id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        if (!generationAdmin.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }
}
