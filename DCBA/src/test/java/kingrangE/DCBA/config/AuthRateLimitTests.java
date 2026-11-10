package kingrangE.DCBA.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitTests {
    private MockHttpServletResponse request(AuthRateLimitFilter filter, String address, String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(address);
        request.addHeader("X-Forwarded-For", java.util.UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }

    @Test
    void limitsCombinedLoginAndSignupAttemptsWithoutTrustingForwardedHeaders() throws Exception {
        AtomicLong time = new AtomicLong();
        AuthRateLimitFilter filter = new AuthRateLimitFilter(time::get);
        for (int i = 0; i < 10; i++) assertThat(request(filter, "client", "POST", i % 2 == 0 ? "/login" : "/signup").getStatus()).isEqualTo(200);
        assertThat(request(filter, "client", "POST", "/login").getStatus()).isEqualTo(429);
        assertThat(request(filter, "another", "POST", "/login").getStatus()).isEqualTo(200);
        assertThat(request(filter, "client", "GET", "/login").getStatus()).isEqualTo(200);
        assertThat(request(filter, "client", "POST", "/exercise/save").getStatus()).isEqualTo(200);
        time.set(60_000_000_000L);
        assertThat(request(filter, "client", "POST", "/login").getStatus()).isEqualTo(200);
    }

    @Test
    void concurrentAttemptsCannotExceedBudget() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(() -> 0);
        AtomicInteger accepted = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 30; i++) tasks.add(executor.submit(() -> {
                try {
                    if (request(filter, "client", "POST", "/login").getStatus() == 200) accepted.incrementAndGet();
                } catch (Exception error) { throw new RuntimeException(error); }
            }));
            for (var task : tasks) task.get();
        }
        assertThat(accepted.get()).isEqualTo(10);
    }

    @Test
    void boundsAddressStorageAndRecoversAfterWindowExpires() throws Exception {
        AtomicLong time = new AtomicLong();
        AuthRateLimitFilter filter = new AuthRateLimitFilter(time::get);
        for (int i = 0; i < 10_000; i++) request(filter, "client-" + i, "POST", "/login");
        assertThat(request(filter, "overflow", "POST", "/login").getStatus()).isEqualTo(429);
        time.set(60_000_000_000L);
        assertThat(request(filter, "overflow", "POST", "/login").getStatus()).isEqualTo(200);
    }
}
