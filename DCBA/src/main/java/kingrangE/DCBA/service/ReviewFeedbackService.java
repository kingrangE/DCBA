package kingrangE.DCBA.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewFeedbackService {
    private static final DefaultRedisScript<Long> ADMIT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 1 then return -1 end
        if tonumber(redis.call('GET', KEYS[2]) or '0') >= 10 then
            return math.max(redis.call('TTL', KEYS[2]), 1)
        end
        if tonumber(redis.call('GET', KEYS[3]) or '0') >= 60 then return -2 end
        redis.call('SET', KEYS[1], ARGV[1], 'EX', 20)
        for i = 2, 3 do
            if redis.call('INCR', KEYS[i]) == 1 then redis.call('EXPIRE', KEYS[i], 3600) end
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final URI endpoint;
    private final String apiKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public ReviewFeedbackService(StringRedisTemplate redis, MeterRegistry metrics,
            @Value("${REVIEW_LLM_BASE_URL:http://llm-server:8000}") String baseUrl,
            @Value("${LLM_API_KEY:}") String apiKey) {
        this.redis = redis;
        this.metrics = metrics;
        this.endpoint = URI.create(baseUrl).resolve("/review/feedback");
        this.apiKey = apiKey;
    }

    public ReviewProtocol.Feedback evaluate(Long userId, ReviewProtocol.Request request) {
        Timer.Sample timer = Timer.start(metrics);
        String outcome = "success";
        try {
            if (apiKey.isBlank()) throw unavailable();
            String lock = "exercise:review-feedback:lock:" + userId;
            String token = UUID.randomUUID().toString();
            List<String> keys = List.of(lock, "exercise:review-feedback:user:" + userId, "exercise:review-feedback:global");
            Long result;
            try {
                result = redis.execute(ADMIT, keys, token);
            } catch (RuntimeException failure) {
                throw unavailable();
            }
            if (result == null || result < -1) throw unavailable();
            if (result == -1) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 피드백을 처리하고 있어요.");
            if (result > 0) throw new RateLimit(result);
            try {
                return callModel(request);
            } finally {
                try {
                    redis.execute(RELEASE, List.of(lock), token);
                } catch (RuntimeException failure) {
                    // The lease expires even if Redis is temporarily unavailable during release.
                    metrics.counter("dcba.review.feedback.release.failure").increment();
                }
            }
        } catch (ResponseStatusException failure) {
            outcome = String.valueOf(failure.getStatusCode().value());
            throw failure;
        } finally {
            metrics.counter("dcba.review.feedback.requests", "outcome", outcome).increment();
            timer.stop(metrics.timer("dcba.review.feedback.duration", "outcome", outcome));
        }
    }

    private ReviewProtocol.Feedback callModel(ReviewProtocol.Request request) {
        try {
            var message = HttpRequest.newBuilder(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .timeout(Duration.ofSeconds(13))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ReviewProtocol.JSON.writeValueAsBytes(request)))
                    .build();
            var pending = client.sendAsync(message, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try {
                response = pending.get(13, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException failure) {
                pending.cancel(true);
                throw failure;
            }
            if (response.statusCode() == 504) throw timeout();
            if (response.statusCode() != 200) throw unavailable();
            return ReviewProtocol.feedback(response.body(), request.criteria());
        } catch (TimeoutException failure) {
            throw timeout();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof HttpTimeoutException) throw timeout();
            throw unavailable();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static ResponseStatusException timeout() {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "피드백 응답이 늦어지고 있어요. 다시 시도해 주세요.");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금은 AI 피드백을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.");
    }

    private static final class RateLimit extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        private RateLimit(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "피드백 요청 한도에 도달했어요.");
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
