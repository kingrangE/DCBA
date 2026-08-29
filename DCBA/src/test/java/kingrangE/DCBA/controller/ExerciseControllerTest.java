package kingrangE.DCBA.controller;

import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.service.ExerciseGenerationQueueService;
import kingrangE.DCBA.service.ExerciseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExerciseControllerTest {

    @Mock
    private ExerciseService exerciseService;

    @Mock
    private ExerciseGenerationQueueService queueService;

    private ExerciseController controller;

    @BeforeEach
    void setUp() {
        controller = new ExerciseController(exerciseService, queueService);
    }

    @Test
    void generationRequestRequiresLogin() {
        MockHttpSession session = new MockHttpSession();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.requestExerciseGeneration(
                Subject.ALGORITHM, Level.EASY, session, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/");
        verify(queueService, never()).enqueue(Subject.ALGORITHM, Level.EASY);
    }

    @Test
    void generationRequestEnqueuesTaskAndReturnsToFilteredDashboard() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User("tester", "password"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        when(queueService.enqueue(Subject.OPERATING_SYSTEM, Level.MEDIUM)).thenReturn(2L);

        String view = controller.requestExerciseGeneration(
                Subject.OPERATING_SYSTEM, Level.MEDIUM, session, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/dashboard");
        verify(queueService).enqueue(Subject.OPERATING_SYSTEM, Level.MEDIUM);
        assertThat(redirectAttributes.getFlashAttributes().get("generationMessage").toString())
                .contains("운영체제", "Level 2", "대기열: 2");
        assertThat(redirectAttributes.getAttribute("subject")).isEqualTo("OPERATING_SYSTEM");
        assertThat(redirectAttributes.getAttribute("level")).isEqualTo("MEDIUM");
    }
}
