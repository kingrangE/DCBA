package kingrangE.DCBA.controller;

import jakarta.servlet.http.HttpSession;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.LoginForm;
import kingrangE.DCBA.dto.SignUpForm;
import kingrangE.DCBA.dto.api.MessageResponse;
import kingrangE.DCBA.dto.api.SlackUpdateRequest;
import kingrangE.DCBA.dto.api.UserResponse;
import kingrangE.DCBA.exception.UnauthorizedException;
import kingrangE.DCBA.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private static final String LOGIN_USER = "loginUser";

    private final UserService userService;

    @PostMapping("/auth/login")
    public UserResponse login(@RequestBody LoginForm loginForm, HttpSession session) {
        validateCredentials(loginForm.getName(), loginForm.getPassword());

        try {
            User user = userService.login(loginForm.getName().trim(), loginForm.getPassword());
            session.setAttribute(LOGIN_USER, user);
            return UserResponse.from(user);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
    }

    @PostMapping("/auth/signup")
    public ResponseEntity<MessageResponse> signup(@RequestBody SignUpForm signUpForm) {
        validateCredentials(signUpForm.getName(), signUpForm.getPassword());

        try {
            userService.signUp(signUpForm.getName().trim(), signUpForm.getPassword());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new MessageResponse("회원가입이 완료되었습니다."));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public UserResponse currentUser(HttpSession session) {
        User sessionUser = requireUser(session);
        return UserResponse.from(userService.getUser(sessionUser.getId()));
    }

    @PatchMapping("/users/me/slack")
    public UserResponse updateSlackId(@RequestBody SlackUpdateRequest request, HttpSession session) {
        User sessionUser = requireUser(session);
        String slackId = request.slackId() == null ? "" : request.slackId().trim();
        if (slackId.isEmpty()) {
            throw new IllegalArgumentException("Slack ID를 입력해 주세요.");
        }

        userService.updateSlackId(sessionUser.getId(), slackId);
        User updatedUser = userService.getUser(sessionUser.getId());
        session.setAttribute(LOGIN_USER, updatedUser);
        return UserResponse.from(updatedUser);
    }

    private User requireUser(HttpSession session) {
        User user = (User) session.getAttribute(LOGIN_USER);
        if (user == null) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        return user;
    }

    private void validateCredentials(String name, String password) {
        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("이름과 비밀번호를 모두 입력해 주세요.");
        }
    }
}
