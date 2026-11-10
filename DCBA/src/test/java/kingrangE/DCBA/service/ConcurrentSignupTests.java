package kingrangE.DCBA.service;

import kingrangE.DCBA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentSignupTests {
    @Autowired UserService service;
    @Autowired UserRepository users;

    @Test
    void concurrentSignupsCreateOneAccountAndExplainAllConflicts() throws Exception {
        String name = "race" + UUID.randomUUID().toString().substring(0, 6);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> {
                start.await();
                try {
                    service.signUp(name, "password");
                    return true;
                } catch (UserRequestException conflict) {
                    assertThat(conflict).hasMessage("해당 닉네임을 사용하는 유저가 존재합니다.");
                    return false;
                }
            }));
            start.countDown();
            int created = 0;
            for (Future<Boolean> task : tasks) if (task.get(30, TimeUnit.SECONDS)) created++;
            assertThat(created).isEqualTo(1);
            assertThat(users.findByName(name)).isPresent();
        } finally {
            users.findByName(name).ifPresent(users::delete);
        }
    }
}
