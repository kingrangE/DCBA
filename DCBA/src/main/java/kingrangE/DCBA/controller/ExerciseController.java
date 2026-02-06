package kingrangE.DCBA.controller;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.service.ExerciseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import kingrangE.DCBA.domain.User;
import jakarta.servlet.http.HttpSession;

@Controller
@RequiredArgsConstructor // 자동 Constructor 생성
public class ExerciseController {

    private final ExerciseService exerciseService;

    /**
     * 대시보드 메인 화면 요청 시 로직
     * @param subject 과목 이름 (Enum)
     * @param level 레벨 (Enum)
     * @param pageable 페이지 정보
     * @param model 모델 객체 (HTML 파일 전달용)
     * @param session Session 정보 (Login 검사용)
     * @return dashboard.html
     */
    @GetMapping("/dashboard")
    public String dashboardMain(@RequestParam(required = false) Subject subject, // 필터링 조건 (Optional)
            @RequestParam(required = false) Level level, // 필터링 조건 (Optional)
            @PageableDefault(size = 9) Pageable pageable, // page번호와 size에 따라 Page 관리 (기본 9개)
            Model model, // Controller 데이터를 HTML 파일에서 이용하기 위함
            HttpSession session) { // 로그인해야 접근 가능하도록 Session정보 저장용

        if (session.getAttribute("loginUser") == null) { // login한 정보가 없으면
            return "redirect:/"; // 처음으로
        }

        User user = (User) session.getAttribute("loginUser"); // 로그인한 유저 정보 가져옴

        //현재 Pageable 정보를 기반으로 문제 페이지를 가져옴
        Page<Exercise> exercisePage = exerciseService.getExercises(user.getId(), subject, level, pageable); //

        // 현재 페이지 정보 추가 ( 페이지 정보를 전체로 바꿔서, ... 을 나타낼 수 있게 하면 더 좋을듯)
        model.addAttribute("exercises", exercisePage.getContent()); // List<Exercise> 객체 저장
        model.addAttribute("page", exercisePage);
        model.addAttribute("currentSubject", subject);
        model.addAttribute("currentLevel", level);

        // 현재 과목 및 레벨 정보 추가
        model.addAttribute("subjects", Subject.values());
        model.addAttribute("levels", Level.values());

        // 저장한 문제들과 banned한 문제들을 표시하기 위한 id들 추가
        model.addAttribute("savedIds", exerciseService.getSavedExerciseIds(user.getId()));
        model.addAttribute("bannedIds", exerciseService.getBannedExerciseIds(user.getId()));

        // dashboard.html view resolver 전달ㄴ
        return "dashboard";
    }

    /**
     * 문제 저장 요청시 로직
     * @param exerciseId 문제 Id
     * @param session Session 정보 (유저 정보 얻는 용도)
     * @return dashboard.html(redirect)
     */
    @PostMapping("/exercise/save")
    public String saveExercise(@RequestParam Long exerciseId, HttpSession session) {
        // 저장하려는 문제와 HttpSession(User 정보 얻는용도)를 받음

        // User 정보 GET
        User user = (User) session.getAttribute("loginUser");

        // login 안했으면 처음으로
        if (user == null) {
            return "redirect:/";
        }

        // login 했으면, 저장
        exerciseService.saveExercise(user.getId(), exerciseId);

        // 로직 끝났으면 다시 dashboard로
        return "redirect:/dashboard";
    }

    /**
     * 문제 저장 취소 로직
     * @param exerciseId 문제 Id
     * @param session Session 정보 (유저 정보 얻는 용도)
     * @return dashboard.html(redirect)
     */
    @PostMapping("/exercise/save/cancel")
    public String cancelSaveExercise(@RequestParam Long exerciseId, HttpSession session) {
        //취소를 위해 문제 ID와 Session을 받음
        User user = (User) session.getAttribute("loginUser");

        //User가 없다면
        if (user == null) {
            //로그인하쇼
            return "redirect:/";
        }
        //있으면 취소 로직 들어가쇼
        exerciseService.cancelSaveExercise(user.getId(), exerciseId);
        //끝났으면 dashboard로
        return "redirect:/dashboard";
    }

    /**
     * 문제 금지 요청 로직
     * @param exerciseId 문제 Id
     * @param session Session 정보 (유저 정보 얻는 용도)
     * @return dashboard.html(redirect)
     */
    @PostMapping("/exercise/ban")
    public String banExercise(@RequestParam Long exerciseId, HttpSession session) {
        // 차단할 운동 ID 및 Session 정보
        // 유저 정보 Get
        User user = (User) session.getAttribute("loginUser");
        if (user == null) { //없으면 로그인하쇼
            return "redirect:/";
        }
        // 있으면 차단 로직
        exerciseService.banExercise(user.getId(), exerciseId);

        // 끝났으면 대시보드로
        return "redirect:/dashboard";
    }

    /**
     * 문제 금지 취소 로직
     * @param exerciseId 문제 Id
     * @param session Session 정보 (유저 정보 얻는 용도)
     * @return dashboard.html(redirect)
     */
    @PostMapping("/exercise/ban/cancel")
    public String cancelBanExercise(@RequestParam Long exerciseId, HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) {
            return "redirect:/";
        }
        exerciseService.cancelBanExercise(user.getId(), exerciseId);
        return "redirect:/dashboard";
    }

    /**
     * 선택한 문제들 확인 화면 요청시 로직
     * @param pageable 페이지 정보
     * @param model 모델 객체(HTML 전달용)
     * @param session Session 정보 (로그인 확인)
     * @return dashboard.html
     */
    @GetMapping("/dashboard/selected")
    public String dashboardSelected(@PageableDefault(size = 9) Pageable pageable, // 페이지 정보
                                    Model model, // HTML에 전달할 모델 정보
                                    HttpSession session) { //Session 관리를 위한 Session정보
        // Session에서 유저 정보 가져옴
        User user = (User) session.getAttribute("loginUser");
        if (user == null) { //로그인 안 했으면 해
            return "redirect:/";
        }

        // 선택한 문제 가져와
        Page<Exercise> exercisePage = exerciseService.getSelectedExercises(user.getId(), pageable);

        // 문제를 보여주기 위해 Model에 정보 추가
        model.addAttribute("exercises", exercisePage.getContent());
        model.addAttribute("page", exercisePage);

        // 다 같은 HTML 파일을 사용하기에 viewType 파라미터 설정해서 다르게 보이도록 함
        model.addAttribute("viewType", "selected");
        model.addAttribute("subjects", Subject.values());
        model.addAttribute("levels", Level.values());

        // 저장한거랑 밴한거 정보 가져와서 표시하도록 함.
        model.addAttribute("savedIds", exerciseService.getSavedExerciseIds(user.getId()));
        model.addAttribute("bannedIds", exerciseService.getBannedExerciseIds(user.getId()));

        return "dashboard";
    }

    /**
     * 금지한 문제들 화면 요청시 로직
     * @param pageable 페이지 정보
     * @param model 모델 객체(HTML 전달용)
     * @param session Session 정보 (로그인 확인)
     * @return dashboard.html
     */
    @GetMapping("/dashboard/banned")
    public String dashboardBanned(@PageableDefault(size = 9) Pageable pageable, Model model, HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) {
            return "redirect:/";
        }

        Page<Exercise> exercisePage = exerciseService.getBannedExercises(user.getId(), pageable);

        model.addAttribute("exercises", exercisePage.getContent());
        model.addAttribute("page", exercisePage);
        model.addAttribute("viewType", "banned");

        model.addAttribute("subjects", Subject.values());
        model.addAttribute("levels", Level.values());

        model.addAttribute("savedIds", exerciseService.getSavedExerciseIds(user.getId()));
        model.addAttribute("bannedIds", exerciseService.getBannedExerciseIds(user.getId()));

        return "dashboard";
    }
}
