package kingrangE.DCBA.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.LoginForm;
import kingrangE.DCBA.dto.SignUpForm;
import kingrangE.DCBA.repository.UserRepository;
import kingrangE.DCBA.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class UserController {

    private final UserService userService;
    @Autowired
    public UserController(UserRepository userRepo, UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/")
    public String loginForm(Model model) {
        model.addAttribute("loginForm", new LoginForm()); // 빈 객체 전달
        return "login";
    }

    @PostMapping("/login")
    public String login(@ModelAttribute LoginForm loginForm, HttpSession session){
        User user = userService.login(loginForm.getName(), loginForm.getPassword());
        session.setAttribute("loginUser", user);
        return "redirect:/dashboard";
    }
    @GetMapping("/signup")
    public String signupForm(Model model){
        model.addAttribute("signUpForm",new SignUpForm());
        return "signup";
    }
    @PostMapping("/signup")
    public String signup(@ModelAttribute SignUpForm signUpForm) {
        userService.signUp(signUpForm.getId(),
                signUpForm.getName(),
                signUpForm.getPassword(),
                signUpForm.getMainLanguages());
        return "redirect:/";
    }

    @ExceptionHandler({RuntimeException.class})
    public String handlerException(RuntimeException e, HttpServletRequest request, RedirectAttributes redirectAttributes){
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
