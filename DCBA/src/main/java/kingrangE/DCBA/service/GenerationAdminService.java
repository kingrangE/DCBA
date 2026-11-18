package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GenerationAdminService {

    static final String PAUSE_KEY = "exercise:auto_generation_paused";

    private final StringRedisTemplate redis;
    private final long adminUserId;

    public GenerationAdminService(StringRedisTemplate redis, @Value("${DCBA_ADMIN_USER_ID:0}") long adminUserId) {
        this.redis = redis;
        this.adminUserId = adminUserId;
    }

    public boolean isAdmin(User user) {
        return adminUserId > 0 && Long.valueOf(adminUserId).equals(user.getId());
    }

    public boolean isPaused() {
        return Boolean.TRUE.equals(redis.hasKey(PAUSE_KEY));
    }

    public void pause() {
        redis.opsForValue().set(PAUSE_KEY, "1");
    }
}
