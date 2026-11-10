package kingrangE.DCBA.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final Map<String, Integer> attempts = new HashMap<>();
    private final LongSupplier clock;
    private long windowStarted;

    public AuthRateLimitFilter() {
        this(System::nanoTime);
    }

    AuthRateLimitFilter(LongSupplier clock) {
        this.clock = clock;
        this.windowStarted = clock.getAsLong();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !request.getMethod().equals("POST") || !(path.equals("/login") || path.equals("/signup"));
    }

    private synchronized boolean allow(String address) {
        long now = clock.getAsLong();
        if (now - windowStarted >= 60_000_000_000L) {
            attempts.clear();
            windowStarted = now;
        }
        int count = attempts.getOrDefault(address, 0);
        if (count >= 10 || (count == 0 && attempts.size() >= 10_000)) return false;
        attempts.put(address, count + 1);
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // shortcut: per-process limits suit one Java server; share counters at the trusted ingress before scaling out.
        if (allow(request.getRemoteAddr())) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }
    }
}
