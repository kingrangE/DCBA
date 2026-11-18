package kingrangE.DCBA.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewFeedbackHttpTests {
    static final List<ReviewProtocol.Criterion> RUBRIC = List.of(
            new ReviewProtocol.Criterion("first", "첫 기준", "설명"),
            new ReviewProtocol.Criterion("second", "둘째 기준", "설명"));
    static final ReviewProtocol.Request QUESTION = new ReviewProtocol.Request("Question", "EASY", "Model answer", RUBRIC, "Answer");
    static final String VALID = """
            {"criteria":[{"id":"second","status":"missing","comment":"다음에 써 보세요."},
                         {"id":"first","status":"met","comment":"정확해요."}],"nextStep":"연습해 보세요."}
            """;

    @Test
    void authenticatesAndValidatesModelWithoutLeakingRawFailures() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L);
            var status = new AtomicInteger(200);
            var body = new AtomicReference<>(VALID);
            var auth = new AtomicReference<String>();
            var input = new AtomicReference<String>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                server.createContext("/review/feedback", exchange -> {
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    input.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] reply = body.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), reply.length);
                    try (var stream = exchange.getResponseBody()) { stream.write(reply); }
                });
                server.start();
                var client = new ReviewFeedbackService(redis, registry, "http://127.0.0.1:" + server.getAddress().getPort(), "inside");
                var result = client.evaluate(1L, QUESTION);
                assertThat(auth.get()).isEqualTo("Bearer inside");
                assertThat(ReviewProtocol.JSON.readTree(input.get()).get("answer").asText()).isEqualTo("Answer");
                assertThat(ReviewProtocol.JSON.readTree(input.get()).has("userId")).isFalse();
                assertThat(result.criteria()).extracting(ReviewProtocol.CriterionFeedback::label)
                        .containsExactly("첫 기준", "둘째 기준");

                body.set(VALID.replace("\"first\"", "\"second\""));
                assertFailure(503, () -> client.evaluate(1L, QUESTION));
                body.set(VALID.replace("\"nextStep\"", "\"private_output\""));
                assertFailure(503, () -> client.evaluate(1L, QUESTION));
                status.set(504);
                assertFailure(504, () -> client.evaluate(1L, QUESTION));
                assertThat(registry.get("dcba.review.feedback.requests").tag("outcome", "success").counter().count()).isEqualTo(1);
            } finally {
                server.stop(0);
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void rejectsBusyLimitedAndUnavailableRequestsBeforeCallingHttp() {
        var registry = new SimpleMeterRegistry();
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            var client = new ReviewFeedbackService(redis, registry, "http://127.0.0.1:1", "inside");
            when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(-1L, 42L, -2L)
                    .thenThrow(new IllegalStateException("private Redis URL"));
            assertFailure(409, () -> client.evaluate(1L, QUESTION));
            assertThatThrownBy(() -> client.evaluate(1L, QUESTION))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode().value()).isEqualTo(429);
                        assertThat(error.getHeaders().getFirst("Retry-After")).isEqualTo("42");
                    });
            assertFailure(503, () -> client.evaluate(1L, QUESTION));
            assertFailure(503, () -> client.evaluate(1L, QUESTION));
        } finally {
            registry.close();
        }
    }

    @Test
    void timesOutIfInternalServerSendsHeadersButStallsTheBody() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L);
        var registry = new SimpleMeterRegistry();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var finish = new CountDownLatch(1);
        server.createContext("/review/feedback", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                finish.await(18, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new ReviewFeedbackService(redis, registry, "http://127.0.0.1:" + server.getAddress().getPort(), "inside");
            assertFailure(504, () -> client.evaluate(1L, QUESTION));
        } finally {
            finish.countDown();
            server.stop(0);
            registry.close();
        }
    }

    static void assertFailure(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(status);
            assertThat(error.getReason()).doesNotContain("private");
        });
    }
}
