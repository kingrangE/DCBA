package kingrangE.DCBA.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExerciseGenerationQueueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    private ObjectMapper objectMapper;
    private ExerciseGenerationQueueService queueService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        queueService = new ExerciseGenerationQueueService(redisTemplate, objectMapper);
    }

    @Test
    void enqueueUsesPythonConsumerQueueContract() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPush(eq(ExerciseGenerationQueueService.QUEUE_KEY), anyString())).thenReturn(3L);

        Long queueSize = queueService.enqueue(Subject.COMPUTER_NETWORK, Level.HARD);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq("exercise:generation_queue"), payloadCaptor.capture());

        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.get("subject").asText()).isEqualTo("COMPUTER_NETWORK");
        assertThat(payload.get("level").asText()).isEqualTo("HARD");
        assertThat(payload.get("subject_display").asText()).isEqualTo("컴퓨터 네트워크");
        assertThat(payload.get("level_display").asText()).isEqualTo("난이도 3 (심화)");
        assertThat(queueSize).isEqualTo(3L);
    }
}
