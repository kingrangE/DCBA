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
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Service
@RequiredArgsConstructor
public class ExerciseGenerationQueueService {

    static final String QUEUE_KEY = "exercise:generation_stream";
    private static final DefaultRedisScript<Long> ENQUEUE = new DefaultRedisScript<>("""
        local depth = redis.call('XLEN', KEYS[1]) + redis.call('LLEN', KEYS[2])
        if depth >= 1000 then return -1 end
        redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
        return depth + 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Python LLM consumer가 처리할 문제 생성 작업을 Redis 큐에 추가한다.
     *
     * @return 작업 추가 후 큐 크기
     */
    public Long enqueue(Subject subject, Level level) {
        Map<String, String> task = new LinkedHashMap<>();
        task.put("task_id", UUID.randomUUID().toString());
        task.put("subject", subject.name());
        task.put("level", level.name());
        task.put("subject_display", subject.getSubjectName());
        task.put("level_display", level.getPromptName());

        try {
            String payload = objectMapper.writeValueAsString(task);
            Long depth = redisTemplate.execute(ENQUEUE, List.of(QUEUE_KEY, "exercise:generation_queue"), payload);
            if (depth == null || depth < 0) throw new IllegalStateException("문제 생성 요청이 가득 찼습니다.");
            return depth;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("문제 생성 요청을 직렬화하지 못했습니다.", e);
        }
    }
}
