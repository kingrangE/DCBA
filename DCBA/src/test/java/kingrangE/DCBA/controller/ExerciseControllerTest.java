package kingrangE.DCBA.controller;

import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.api.GenerationRequest;
import kingrangE.DCBA.dto.api.GenerationResponse;
import kingrangE.DCBA.exception.UnauthorizedException;
import kingrangE.DCBA.service.ExerciseGenerationQueueService;
import kingrangE.DCBA.service.ExerciseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        assertThatThrownBy(() -> controller.requestGeneration(
                new GenerationRequest(Subject.ALGORITHM, Level.EASY, null), session))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인이 필요합니다.");

        verify(queueService, never()).enqueue(Subject.ALGORITHM, Level.EASY, 15L, null);
    }

    @Test
    void generationRequestEnqueuesTaskAndReturnsAcceptedResponse() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User(15L, "tester", "password", null, null));
        when(queueService.enqueue(Subject.OPERATING_SYSTEM, Level.MEDIUM, 15L, null))
                .thenReturn(new ExerciseGenerationQueueService.Accepted("request-id", 2L));

        ResponseEntity<GenerationResponse> response = controller.requestGeneration(
                new GenerationRequest(Subject.OPERATING_SYSTEM, Level.MEDIUM, null), session);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("운영체제", "Level 2");
        assertThat(response.getBody().queueSize()).isEqualTo(2);
        assertThat(response.getBody().requestId()).isEqualTo("request-id");
        verify(queueService).enqueue(Subject.OPERATING_SYSTEM, Level.MEDIUM, 15L, null);
    }

    @Test
    void trimsLearningIntentAndRejectsMoreThanTwoHundredCodePoints() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User(15L, "tester", "password", null, null));
        String boundary = "🚀".repeat(200);
        when(queueService.enqueue(Subject.DATABASE, Level.HARD, 15L, boundary))
                .thenReturn(new ExerciseGenerationQueueService.Accepted("request-id", 1L));

        var response = controller.requestGeneration(
                new GenerationRequest(Subject.DATABASE, Level.HARD, "\u00a0" + boundary + "\ufeff"), session);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThatThrownBy(() -> controller.requestGeneration(
                new GenerationRequest(Subject.DATABASE, Level.HARD, boundary + "끝"), session))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queueService).enqueue(Subject.DATABASE, Level.HARD, 15L, boundary);
    }
}
