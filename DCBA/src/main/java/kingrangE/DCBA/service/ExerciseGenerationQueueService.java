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
import java.util.Set;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
        local owner_type = redis.call('TYPE', KEYS[4]).ok
        if depth >= 1000 or redis.call('EXISTS', KEYS[3]) ~= 0
            or (owner_type ~= 'none' and owner_type ~= 'zset') then return -1 end
        redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
        redis.call('HSET', KEYS[3], 'owner', ARGV[2], 'status', 'queued', 'subject', ARGV[5], 'level', ARGV[6], 'requested_at', ARGV[4])
        redis.call('ZADD', KEYS[4], ARGV[4], ARGV[3])
        return depth + 1
        """, Long.class);
    private static final DefaultRedisScript<String> RECENT = new DefaultRedisScript<>("""
        local rows = {}
        for _, id in ipairs(redis.call('ZREVRANGE', KEYS[1], 0, 99)) do
            local state = redis.call('HMGET', 'exercise:generation_request:' .. id,
                                    'owner', 'status', 'reason', 'subject', 'level', 'requested_at')
            if state[1] == ARGV[1] then
                table.insert(rows, {id, state[2], state[3], state[4], state[5], state[6]})
            else
                redis.call('ZREM', KEYS[1], id)
            end
        end
        return cjson.encode(rows)
        """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Accepted(String requestId, long queueSize) {}
    public record Status(String status, Long exerciseId, String reason, String message, String completionType) {}
    public record Notification(String requestId, long exerciseId, Instant completedAt, Instant readAt,
                               String completionType) {}
    public record Notifications(List<Notification> items, boolean hasNext) {}
    public record RecentRequest(String requestId, String subject, String level, Instant requestedAt, Status progress) {}

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
            Long depth = redisTemplate.execute(ENQUEUE, List.of(QUEUE_KEY, "exercise:generation_queue",
                            stateKey(requestId), ownerKey(userId)),
                    payload, String.valueOf(userId), requestId, String.valueOf(System.currentTimeMillis()),
                    subject.getSubjectName(), level.getPromptName());
            if (depth == null || depth < 0) throw new IllegalStateException("문제 생성 요청이 가득 찼습니다.");
            return new Accepted(requestId, depth);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("문제 생성 요청을 직렬화하지 못했습니다.", e);
        }
    }

    public Status status(UUID requestId, long userId) {
        Status completed = completedStatus(requestId, userId);
        if (completed != null) return completed;

        var state = redisTemplate.opsForHash().entries(stateKey(requestId.toString()));
        // A delivery can commit after the first lookup and before we read Redis.
        completed = completedStatus(requestId, userId);
        if (completed != null) return completed;
        if (!String.valueOf(userId).equals(state.get("owner"))) throw missing();
        return pendingStatus(String.valueOf(state.get("status")), String.valueOf(state.get("reason")));
    }

    public Long completedExercise(UUID requestId, long userId) {
        var status = completedStatus(requestId, userId);
        return status == null ? null : status.exerciseId();
    }

    private Status completedStatus(UUID requestId, long userId) {
        return jdbc.query("""
                SELECT exercise_id, completion_type FROM generation_task
                WHERE task_id = ? AND requester_id = ? AND completed_at IS NOT NULL
                """, (rs, row) -> new Status("completed", rs.getLong("exercise_id"), null, null,
                rs.getString("completion_type")), requestId.toString(), userId).stream().findFirst().orElse(null);
    }

    public List<RecentRequest> recent(long userId) {
        String encoded = redisTemplate.execute(RECENT, List.of(ownerKey(userId)), String.valueOf(userId));
        try {
            var pending = new java.util.ArrayList<RecentRequest>();
            for (var row : objectMapper.readTree(encoded)) {
                pending.add(new RecentRequest(row.get(0).asText(), row.get(3).asText(), row.get(4).asText(),
                        Instant.ofEpochMilli(row.get(5).asLong()), pendingStatus(row.get(1).asText(), row.get(2).asText())));
            }
            if (pending.isEmpty()) return List.of();
            var ids = pending.stream().map(RecentRequest::requestId).toList();
            var completed = Set.copyOf(new NamedParameterJdbcTemplate(jdbc).queryForList("""
                    SELECT task_id FROM generation_task
                    WHERE requester_id = :owner AND task_id IN (:ids) AND completed_at IS NOT NULL
                    """, Map.of("owner", userId, "ids", ids), String.class));
            return pending.stream().filter(request -> !completed.contains(request.requestId())).toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("생성 상태를 읽지 못했어요.", exception);
        }
    }

    public Notifications notifications(long userId, int page) {
        if (page < 0) throw new IllegalArgumentException("페이지 번호를 확인해 주세요.");
        var rows = jdbc.query("""
                SELECT task_id, exercise_id, completed_at, read_at, completion_type FROM generation_task
                WHERE requester_id = ? AND completed_at IS NOT NULL
                ORDER BY completed_at DESC, task_id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> {
            var readAt = rs.getTimestamp("read_at");
            return new Notification(rs.getString("task_id"), rs.getLong("exercise_id"),
                    rs.getTimestamp("completed_at").toInstant(), readAt == null ? null : readAt.toInstant(),
                    rs.getString("completion_type"));
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

    private static String ownerKey(long userId) {
        return "exercise:generation_requests_by_owner:" + userId;
    }

    private static Status pendingStatus(String status, String reason) {
        if (status.equals("queued") || status.equals("running")) return new Status(status, null, null, null, null);
        if (!status.equals("failed")) throw missing();
        String message = switch (reason) {
            case "out_of_scope" -> "선택한 분야와 학습하고 싶은 내용이 맞지 않아요. 분야나 내용을 바꿔 다시 요청해 주세요.";
            case "generation_limit", "time_limit" -> "설정된 생성 한도 안에 문제를 완성하지 못했어요. 배우고 싶은 내용을 좁혀 다시 요청해 주세요.";
            case "invalid_request" -> "요청 내용을 확인하지 못했어요. 입력을 확인하고 다시 요청해 주세요.";
            case "private_input", "private_output" -> "개인이나 조직을 식별할 수 있는 내용이 감지됐어요. 공개 가능한 CS 개념과 학습 목표로 다시 요청해 주세요.";
            case "search_unavailable", "search_configuration" -> "문제 검색을 사용할 수 없어 요청을 완료하지 못했어요. 잠시 후 다시 요청해 주세요.";
            default -> "일시적인 문제로 생성을 완료하지 못했어요. 잠시 후 다시 요청해 주세요.";
        };
        return new Status("failed", null, reason, message, null);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "생성 요청을 찾을 수 없어요.");
    }
}
