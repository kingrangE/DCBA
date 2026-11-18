package kingrangE.DCBA.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.exception.UnauthorizedException;
import kingrangE.DCBA.service.ReviewProtocol;
import kingrangE.DCBA.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/exercises")
@RequiredArgsConstructor
public class ReviewController {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private final ReviewService reviews;

    @GetMapping("/review-sample")
    public ReviewService.Sample sample(@RequestParam(defaultValue = "random") String mode,
            @RequestParam(required = false) Subject subject, @RequestParam(required = false) Level level,
            HttpSession session, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return reviews.sample(userId(session), mode, subject, level);
    }

    @PutMapping("/{id}/review-again")
    public ResponseEntity<Void> mark(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        Long userId = userId(session);
        if (!(body.get("enabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("다시 풀기 상태를 선택해 주세요.");
        }
        reviews.mark(userId, id, enabled);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{id}/review-feedback", consumes = "application/json")
    public ReviewProtocol.Feedback feedback(@PathVariable Long id, HttpServletRequest request,
            HttpServletResponse response, HttpSession session) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Long userId = userId(session);
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw bodyTooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw bodyTooLarge();
        return reviews.feedback(userId, id, ReviewProtocol.answer(body));
    }

    private static ResponseStatusException bodyTooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "답변 요청의 크기가 너무 커요.");
    }

    private Long userId(HttpSession session) {
        if (!(session.getAttribute("loginUser") instanceof User user)) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        return user.getId();
    }
}
