package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.User;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

public class UserRepositoryTest {

    UserRepository userRepository;

    @Configuration
    static class TestConfig {
        @Bean
        public UserRepository userRepository() {
            return new MemoryUserRepository();
        }
    }

    @BeforeEach
    void setUp() {
        //DIP 구현 (구현체가 변경되더라도 문제 없도록)
        ApplicationContext ac = new AnnotationConfigApplicationContext(TestConfig.class);
        userRepository = ac.getBean("userRepository",UserRepository.class);
    }

    @Test
    @DisplayName("유저 저장/조회 테스트 - 저장 정보와 조회 정보 확인")
    void save() {
        // Given
        User user = new User("abc", "길원", "pw", List.of("kilwon")); // 유저 정보
        // When
        userRepository.save(user); // 유저 저장
        User findUser = userRepository.findById("abc").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isEqualTo(user);
    }

    @Test
    @DisplayName("유저 이름 조회 테스트 - 있을 때")
    void findByName() {
        // Given
        User user = new User("abc", "길원", "pw", List.of("kilwon")); // 유저 정보
        userRepository.save(user); // 유저 저장
        // When
        User findUser = userRepository.findByName("길원").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isEqualTo(user);
    }

    @Test
    @DisplayName("유저 이름 조회 테스트 - 없을 때 null")
    void findByNameNull() {
        // Given
        // When
        User findUser = userRepository.findByName("길원1").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isNull();
    }

    @Test
    @DisplayName("유저 ID 조회 테스트 - 있을 때")
    void findById() {
        // Given
        User user = new User("abc", "길원", "pw", List.of("kilwon")); // 유저 정보
        userRepository.save(user); // 유저 저장
        // When
        User findUser = userRepository.findById("abc").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isEqualTo(user);
    }

    @Test
    @DisplayName("유저 ID 조회 테스트 - 없을 때 null")
    void findByIdNull() {
        // Given
        // When
        User findUser = userRepository.findById("abc").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isNull();
    }

    @Test
    @DisplayName("유저 삭제 테스트")
    void deleteById() {
        // Given
        User user = new User("abc", "길원", "pw", List.of("kilwon")); // 유저 정보
        userRepository.save(user); // 유저 저장
        // When
        userRepository.deleteById("abc"); // 유저 삭제
        User findUser = userRepository.findById("abc").orElse(null); // 삭제 확인
        // then
        Assertions.assertThat(findUser).isNull();
    }
}
