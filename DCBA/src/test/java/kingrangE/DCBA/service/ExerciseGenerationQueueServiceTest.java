package kingrangE.DCBA.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExerciseGenerationQueueServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExerciseGenerationQueueService service = new ExerciseGenerationQueueService(redis, jdbc);

    @Test
    void enqueuesTheServerOwnedTaskAndCommitsInitialStateInTheSameScript() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), eq("42"))).thenReturn(3L);
        var accepted = service.enqueue(Subject.COMPUTER_NETWORK, Level.HARD, 42L, "TCP 흐름 제어");
        var script = ArgumentCaptor.forClass(RedisScript.class);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(script.capture(), eq(List.of("exercise:generation_stream", "exercise:generation_queue",
                "exercise:generation_request:" + accepted.requestId())), payload.capture(), eq("42"));
        var json = new ObjectMapper().readTree(payload.getValue());
        assertThat(json.get("task_id").asText()).isEqualTo(accepted.requestId());
        assertThat(json.get("request_type").asText()).isEqualTo("manual");
        assertThat(json.get("requester_id").asLong()).isEqualTo(42L);
        assertThat(json.get("learning_intent").asText()).isEqualTo("TCP 흐름 제어");
        assertThat(json.get("subject").asText()).isEqualTo("COMPUTER_NETWORK");
        assertThat(json.get("level").asText()).isEqualTo("HARD");
        assertThat(script.getValue().getScriptAsString()).contains("XADD", "HSET");
        assertThat(accepted.queueSize()).isEqualTo(3L);
    }

    @Test
    void onlyOwnerCanReadQueuedOrFailedStatus() {
        var id = UUID.randomUUID();
        var key = "exercise:generation_request:" + id;
        when(redis.opsForHash().entries(key)).thenReturn(Map.of("owner", "42", "status", "queued"));
        assertThat(service.status(id, 42).status()).isEqualTo("queued");
        assertThatThrownBy(() -> service.status(id, 41)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
        when(redis.opsForHash().entries(key)).thenReturn(Map.of("owner", "42", "status", "failed", "reason", "out_of_scope"));
        assertThat(service.status(id, 42).message()).contains("분야나 내용을");
    }
}
