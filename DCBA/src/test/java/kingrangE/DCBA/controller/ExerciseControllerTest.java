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
                new GenerationRequest(Subject.ALGORITHM, Level.EASY), session))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인이 필요합니다.");

        verify(queueService, never()).enqueue(Subject.ALGORITHM, Level.EASY);
    }

    @Test
    void generationRequestEnqueuesTaskAndReturnsAcceptedResponse() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User("tester", "password"));
        when(queueService.enqueue(Subject.OPERATING_SYSTEM, Level.MEDIUM)).thenReturn(2L);

        ResponseEntity<GenerationResponse> response = controller.requestGeneration(
                new GenerationRequest(Subject.OPERATING_SYSTEM, Level.MEDIUM), session);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("운영체제", "Level 2");
        assertThat(response.getBody().queueSize()).isEqualTo(2);
        verify(queueService).enqueue(Subject.OPERATING_SYSTEM, Level.MEDIUM);
    }
}
