package kingrangE.DCBA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExerciseGenerationQueueService {

    static final String QUEUE_KEY = "exercise:generation_queue";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Python LLM consumer가 처리할 문제 생성 작업을 Redis 큐에 추가한다.
     *
     * @return 작업 추가 후 큐 크기
     */
    public Long enqueue(Subject subject, Level level) {
        Map<String, String> task = new LinkedHashMap<>();
        task.put("subject", subject.name());
        task.put("level", level.name());
        task.put("subject_display", subject.getSubjectName());
        task.put("level_display", level.getPromptName());

        try {
            String payload = objectMapper.writeValueAsString(task);
            return redisTemplate.opsForList().rightPush(QUEUE_KEY, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("문제 생성 요청을 직렬화하지 못했습니다.", e);
        }
    }
}
