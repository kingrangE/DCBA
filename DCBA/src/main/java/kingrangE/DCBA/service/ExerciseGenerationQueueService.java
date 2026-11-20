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
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Service
@RequiredArgsConstructor
public class ExerciseGenerationQueueService {

    static final String QUEUE_KEY = "exercise:generation_stream";
    private static final int PAGE_SIZE = 20;
    private static final DefaultRedisScript<Long> ENQUEUE = new DefaultRedisScript<>("""
        local depth = redis.call('XLEN', KEYS[1]) + redis.call('LLEN', KEYS[2])
        if depth >= 1000 or redis.call('EXISTS', KEYS[3]) ~= 0 then return -1 end
        redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
        redis.call('HSET', KEYS[3], 'owner', ARGV[2], 'status', 'queued')
        return depth + 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Accepted(String requestId, long queueSize) {}
    public record Status(String status, Long exerciseId, String reason, String message) {}
    public record Notification(String requestId, long exerciseId, Instant completedAt, Instant readAt) {}
    public record Notifications(List<Notification> items, boolean hasNext) {}

    public Accepted enqueue(Subject subject, Level level, long userId, String learningIntent) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("task_id", requestId);
        task.put("request_type", "manual");
        task.put("requester_id", userId);
        if (learningIntent != null) task.put("learning_intent", learningIntent);
        task.put("subject", subject.name());
        task.put("level", level.name());
        task.put("subject_display", subject.getSubjectName());
        task.put("level_display", level.getPromptName());

        try {
            String payload = objectMapper.writeValueAsString(task);
            Long depth = redisTemplate.execute(ENQUEUE, List.of(QUEUE_KEY, "exercise:generation_queue", stateKey(requestId)),
                    payload, String.valueOf(userId));
            if (depth == null || depth < 0) throw new IllegalStateException("문제 생성 요청이 가득 찼습니다.");
            return new Accepted(requestId, depth);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("문제 생성 요청을 직렬화하지 못했습니다.", e);
        }
    }

    public Status status(UUID requestId, long userId) {
        Long exerciseId = completedExercise(requestId, userId);
        if (exerciseId != null) return new Status("completed", exerciseId, null, null);

        var state = redisTemplate.opsForHash().entries(stateKey(requestId.toString()));
        if (!String.valueOf(userId).equals(state.get("owner"))) throw missing();
        return switch (String.valueOf(state.get("status"))) {
            case "queued" -> new Status("queued", null, null, null);
            case "running" -> {
                // A delivery can commit after the first lookup and before we read Redis.
                exerciseId = completedExercise(requestId, userId);
                yield exerciseId == null ? new Status("running", null, null, null)
                        : new Status("completed", exerciseId, null, null);
            }
            case "failed" -> {
                exerciseId = completedExercise(requestId, userId);
                if (exerciseId != null) yield new Status("completed", exerciseId, null, null);
                String reason = String.valueOf(state.get("reason"));
                yield switch (reason) {
                    case "out_of_scope" -> new Status("failed", null, reason, "선택한 분야와 학습하고 싶은 내용이 맞지 않아요. 분야나 내용을 바꿔 다시 요청해 주세요.");
                    case "generation_limit", "time_limit" -> new Status("failed", null, reason, "설정된 생성 한도 안에 문제를 완성하지 못했어요. 배우고 싶은 내용을 좁혀 다시 요청해 주세요.");
                    case "invalid_request" -> new Status("failed", null, reason, "요청 내용을 확인하지 못했어요. 입력을 확인하고 다시 요청해 주세요.");
                    default -> new Status("failed", null, "temporary_failure", "일시적인 문제로 생성을 완료하지 못했어요. 잠시 후 다시 요청해 주세요.");
                };
            }
            default -> throw missing();
        };
    }

    public Long completedExercise(UUID requestId, long userId) {
        return jdbc.query("""
                SELECT exercise_id FROM generation_task WHERE task_id = ? AND requester_id = ? AND completed_at IS NOT NULL
                """, (rs, row) -> rs.getLong("exercise_id"), requestId.toString(), userId).stream().findFirst().orElse(null);
    }

    public Notifications notifications(long userId, int page) {
        if (page < 0) throw new IllegalArgumentException("페이지 번호를 확인해 주세요.");
        var rows = jdbc.query("""
                SELECT task_id, exercise_id, completed_at, read_at FROM generation_task
                WHERE requester_id = ? AND completed_at IS NOT NULL
                ORDER BY completed_at DESC, task_id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> {
            var readAt = rs.getTimestamp("read_at");
            return new Notification(rs.getString("task_id"), rs.getLong("exercise_id"),
                    rs.getTimestamp("completed_at").toInstant(), readAt == null ? null : readAt.toInstant());
        }, userId, PAGE_SIZE + 1, (long) page * PAGE_SIZE);
        return new Notifications(rows.stream().limit(PAGE_SIZE).toList(), rows.size() > PAGE_SIZE);
    }

    public void markRead(UUID requestId, long userId) {
        if (completedExercise(requestId, userId) == null) throw missing();
        jdbc.update("""
                UPDATE generation_task SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(6))
                WHERE task_id = ? AND requester_id = ?
                """, requestId.toString(), userId);
    }

    private static String stateKey(String requestId) {
        return "exercise:generation_request:" + requestId;
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "생성 요청을 찾을 수 없어요.");
    }
}
