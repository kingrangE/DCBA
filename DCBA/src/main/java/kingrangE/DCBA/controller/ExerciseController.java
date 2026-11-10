package kingrangE.DCBA.controller;

import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.service.ExerciseService;
import org.springframework.data.domain.Slice;
import java.util.List;
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
     * @param before 이 식별자보다 오래된 문제
     * @param model 모델 객체 (HTML 파일 전달용)
     * @param session Session 정보 (Login 검사용)
     * @return dashboard.html
     */
    @GetMapping("/dashboard")
    public String dashboardMain(@RequestParam(required = false) Subject subject,
            @RequestParam(required = false) Level level,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            Model model, HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) return "redirect:/";

        Slice<Exercise> slice = exerciseService.getExercises(user.getId(), subject, level, before, after);
        List<Exercise> exercises = after == null ? slice.getContent() : slice.getContent().reversed();
        addNavigation(model, slice, exercises, before, after);
        model.addAttribute("viewType", "all");
        model.addAttribute("dashboardPath", "/dashboard");
        model.addAttribute("currentSubject", subject);
        model.addAttribute("currentLevel", level);
        model.addAttribute("subjects", Subject.values());
        model.addAttribute("levels", Level.values());
        List<Long> pageIds = exercises.stream().map(Exercise::getId).toList();
        model.addAttribute("savedIds", exerciseService.getSavedExerciseIds(user.getId(), pageIds));
        model.addAttribute("bannedIds", List.of());
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
     * @param before 이 식별자보다 오래된 문제
     * @param model 모델 객체(HTML 전달용)
     * @param session Session 정보 (로그인 확인)
     * @return dashboard.html
     */
    @GetMapping("/dashboard/selected")
    public String dashboardSelected(@RequestParam(required = false) Subject subject,
            @RequestParam(required = false) Level level,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            Model model, HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) return "redirect:/";

        Slice<Exercise> slice = exerciseService.getSelectedExercises(user.getId(), subject, level, before, after);
        List<Exercise> exercises = after == null ? slice.getContent() : slice.getContent().reversed();
        addNavigation(model, slice, exercises, before, after);
        model.addAttribute("viewType", "selected");
        model.addAttribute("dashboardPath", "/dashboard/selected");
        model.addAttribute("currentSubject", subject);
        model.addAttribute("currentLevel", level);
        model.addAttribute("subjects", Subject.values());
        model.addAttribute("levels", Level.values());
        List<Long> pageIds = exercises.stream().map(Exercise::getId).toList();
        model.addAttribute("savedIds", pageIds);
        model.addAttribute("bannedIds", exerciseService.getBannedExerciseIds(user.getId(), pageIds));
        return "dashboard";
    }

    /**
     * 금지한 문제들 화면 요청시 로직
     * @param before 이 식별자보다 오래된 문제
     * @param model 모델 객체(HTML 전달용)
     * @param session Session 정보 (로그인 확인)
     * @return dashboard.html
     */
    @GetMapping("/dashboard/banned")
    public String dashboardBanned(@RequestParam(required = false) Subject subject,
            @RequestParam(required = false) Level level,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            Model model, HttpSession session) {
        User user = (User) session.getAttribute("loginUser");
        if (user == null) return "redirect:/";

        Slice<Exercise> slice = exerciseService.getBannedExercises(user.getId(), subject, level, before, after);
        List<Exercise> exercises = after == null ? slice.getContent() : slice.getContent().reversed();
        addNavigation(model, slice, exercises, before, after);
        model.addAttribute("viewType", "banned");
        model.addAttribute("dashboardPath", "/dashboard/banned");
        model.addAttribute("currentSubject", subject);
        model.addAttribute("currentLevel", level);
        model.addAttribute("subjects", Subject.values());
        model.addAttribute("levels", Level.values());
        List<Long> pageIds = exercises.stream().map(Exercise::getId).toList();
        model.addAttribute("savedIds", exerciseService.getSavedExerciseIds(user.getId(), pageIds));
        model.addAttribute("bannedIds", pageIds);
        return "dashboard";
    }

    private void addNavigation(Model model, Slice<Exercise> slice, List<Exercise> exercises, Long before, Long after) {
        model.addAttribute("exercises", exercises);
        model.addAttribute("hasCursor", before != null || after != null);
        model.addAttribute("hasPrevious", after == null ? before != null : slice.hasNext());
        model.addAttribute("hasNext", after != null || slice.hasNext());
        model.addAttribute("previousCursor", exercises.isEmpty() ? null : exercises.getFirst().getId());
        model.addAttribute("nextCursor", exercises.isEmpty() ? null : exercises.getLast().getId());
    }

}
