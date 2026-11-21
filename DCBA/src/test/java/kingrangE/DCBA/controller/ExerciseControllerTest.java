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

    @Test
    void acceptsDefaultPortExpressionsInLearningIntent() {
        var session = new MockHttpSession();
        session.setAttribute("loginUser", new User(15L, "tester", "password", null, null));
        for (String port : new String[]{"PostgreSQL 5432", "Redis 6379", "MariaDB 3306",
                "PostgreSQL-5432", "Redis_6379", "MariaDB · 3306"}) {
            for (String intent : new String[]{port, port + " 기본 포트 확인 방법을 공부합니다."}) {
                when(queueService.enqueue(Subject.DATABASE, Level.HARD, 15L, intent))
                        .thenReturn(new ExerciseGenerationQueueService.Accepted("request-id", 1L));
                var response = controller.requestGeneration(
                        new GenerationRequest(Subject.DATABASE, Level.HARD, intent), session);
                assertThat(response.getStatusCode().value()).isEqualTo(202);
                verify(queueService).enqueue(Subject.DATABASE, Level.HARD, 15L, intent);
            }
        }
    }

    @Test
    void rejectsIdentifyingInputBeforeEnqueueing() {
        var session = new MockHttpSession();
        session.setAttribute("loginUser", new User(15L, "tester", "password", null, null));
        for (String intent : new String[]{"qa · ７", "개인 @ example . invalid", "계정 ID: acme-west",
                "dummy-12345", "격리 수준과 관련해 dummy-12345 예시", "ＤＵＭＭＹ — １２３４５",
                "PostgreSQL 5432 기본 포트와 dummy-12345", "Redis_6379 기본 포트와 개인 @ example . invalid",
                "MariaDB · 3306 기본 포트와 계정 ID: acme-west", "Redis 6379 기본 포트와 조직 ID: qa · ７",
                "PostgreSQL-5432 기본 포트와 ＤＵＭＭＹ — １２３４５", "조직 ID: Redis 6379", "Redis-12345", "Redis6379"}) {
            assertThatThrownBy(() -> controller.requestGeneration(
                    new GenerationRequest(Subject.DATABASE, Level.HARD, intent), session))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("공개 가능한 CS 개념");
        }
        org.mockito.Mockito.verifyNoInteractions(queueService);
    }
}
