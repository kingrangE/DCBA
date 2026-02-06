package kingrangE.DCBA.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.LoginForm;
import kingrangE.DCBA.dto.SignUpForm;
import kingrangE.DCBA.repository.UserRepository;
import kingrangE.DCBA.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * URL 접속 시 메인 페이지 (Login)
     * @param model HTML 파일에 전달할 모델 객체
     * @return login.html
     */
    @GetMapping("/")
    public String loginForm(Model model) {
        // 로그인을 위한 LoginForm
        model.addAttribute("loginForm", new LoginForm());
        return "login";
    }

    /**
     * login 요청 시 로직
     * @param loginForm LoginFrom DTO
     * @param session Session (login 결과 저장)
     * @return dashboard(redirect)
     */
    @PostMapping("/login")
    public String login(@ModelAttribute LoginForm loginForm, HttpSession session) {

        // 이름과 비밀번호로 로그인 진행
        User user = userService.login(loginForm.getName(), loginForm.getPassword());

        // User Login Session 유지를 위한 Attribute 추가
        session.setAttribute("loginUser", user);

        return "redirect:/dashboard";
    }

    /**
     * 회원가입 화면 요청 시 로직
     * @param model HTML 파일에 전달할 모델 객체
     * @return signup.html
     */
    @GetMapping("/signup")
    public String signupForm(Model model) {
        // 회원가입을 위한 회원가입 Form 전달
        model.addAttribute("signUpForm", new SignUpForm());
        return "signup";
    }

    /**
     * 회원가입 요청 시, 처리 로직
     * @param signUpForm 회원가입 형식 DTO
     * @return login(redirect)
     */
    @PostMapping("/signup")
    public String signup(@ModelAttribute SignUpForm signUpForm) {
        userService.signUp(signUpForm.getName(),
                signUpForm.getPassword());
        return "redirect:/";
    }


    /**
     * UserController에서 발생한 모든 종류의 Runtime Error 발생 시, 처리하기 위한 Handler
     * @param e Error
     * @param request Error가 발생한 HTTP 요청
     * @param redirectAttributes 데이터 전달하기 위한 객체 (Error Message를 전달하기 위함)
     * @return
     */
    @ExceptionHandler({ RuntimeException.class })
    public String handlerException(RuntimeException e, HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        // Error 메시지를 같이 보내주기 위함
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());

        // 현재 요청이 어디서 왔는지 확인해서 해당 페이지로 돌려보냄
        String referer = request.getHeader("Referer");
        if (referer != null && referer.contains("/signup")) {
            return "redirect:/signup?error";
        }
        return "redirect:/?error";
    }
}
