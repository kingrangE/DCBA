package kingrangE.DCBA.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static kingrangE.DCBA.service.ReviewFeedbackHttpTests.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "REVIEW_TEST_REDIS_PORT", matches = "[0-9]+")
class ReviewFeedbackRedisTests {
    @Test
    void enforcesUserAndGlobalLimitsAndReleasesOnlyOwnedLeases() throws Exception {
        int port = Integer.parseInt(System.getenv("REVIEW_TEST_REDIS_PORT"));
        var config = new RedisStandaloneConfiguration("127.0.0.1", port);
        config.setDatabase(15);
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        String prefix = "exercise:review-feedback:";
        String user = prefix + "user:15";
        String global = prefix + "global";
        String lock = prefix + "lock:15";
        List<String> keys = List.of(user, global, lock);
        redis.delete(keys);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var registry = new SimpleMeterRegistry();
        try (var executor = Executors.newSingleThreadExecutor()) {
            server.createContext("/review/feedback", exchange -> {
                exchange.getRequestBody().readAllBytes();
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] reply = VALID.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, reply.length);
                try (var body = exchange.getResponseBody()) { body.write(reply); }
            });
            server.start();
            var service = new ReviewFeedbackService(redis, registry, "http://127.0.0.1:" + server.getAddress().getPort(), "inside");
            var first = executor.submit(() -> service.evaluate(15L, QUESTION));
            try {
                assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
                assertThat(redis.getExpire(lock)).isBetween(1L, 20L);
                assertThat(redis.getExpire(user)).isBetween(3590L, 3600L);
                assertThat(redis.getExpire(global)).isBetween(3590L, 3600L);
                assertFailure(409, () -> service.evaluate(15L, QUESTION));
                assertThat(redis.opsForValue().get(user)).isEqualTo("1");
                redis.opsForValue().set(lock, "new-owner", Duration.ofSeconds(20));
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertThat(redis.opsForValue().get(lock)).isEqualTo("new-owner");
            redis.delete(lock);

            redis.opsForValue().set(user, "9", Duration.ofSeconds(60));
            service.evaluate(15L, QUESTION);
            assertThat(redis.opsForValue().get(user)).isEqualTo("10");
            assertThat(redis.getExpire(user)).isBetween(1L, 60L);
            assertFailure(429, () -> service.evaluate(15L, QUESTION));
            assertThat(calls.get()).isEqualTo(2);

            redis.expire(user, Duration.ofMillis(1));
            Thread.sleep(20);
            redis.opsForValue().set(global, "59", Duration.ofSeconds(60));
            service.evaluate(15L, QUESTION);
            assertThat(redis.opsForValue().get(user)).isEqualTo("1");
            assertThat(redis.opsForValue().get(global)).isEqualTo("60");
            assertFailure(503, () -> service.evaluate(15L, QUESTION));
            assertThat(calls.get()).isEqualTo(3);
            assertThat(redis.hasKey(lock)).isFalse();
        } finally {
            server.stop(0);
            registry.close();
            redis.delete(keys);
            factory.destroy();
        }
    }
}
