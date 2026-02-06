package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.User;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
public class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("유저 저장/조회 테스트 - 저장 정보와 조회 정보 확인")
    void save() {
        // Given
        User user = new User("길원", "pw"); // 유저 정보
        // When
        userRepository.save(user); // 유저 저장
        // Then
        User findUser = userRepository.findById(user.getId()).orElse(null); // 유저 조회
        Assertions.assertThat(findUser).isNotNull(); // 찾아야 하고
        Assertions.assertThat(findUser).isEqualTo(user); // 같아야 함
    }

    @Test
    @DisplayName("유저 ID 조회 테스트 - 없을 때 null")
    void findByIdNull() {
        // Given
        // When
        User findUser = userRepository.findById(1L).orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isNull(); // 없어야 함
    }

    @Test
    @DisplayName("유저 이름 조회 테스트 - 있을 때")
    void findByName() {
        // Given
        User user = new User("길원", "pw"); // 유저 정보
        userRepository.save(user); // 유저 저장
        // When
        User findUser = userRepository.findByName("길원").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isNotNull(); // 찾아야 하고
        Assertions.assertThat(findUser).isEqualTo(user); // 같아야 함.
    }

    @Test
    @DisplayName("유저 이름 조회 테스트 - 없을 때 null")
    void findByNameNull() {
        // Given
        // When
        User findUser = userRepository.findByName("길원").orElse(null); // 유저 조회
        // Then
        Assertions.assertThat(findUser).isNull(); // 없어야 함
    }

    @Test
    @DisplayName("유저 삭제 테스트")
    void deleteById() {
        // Given
        User user = new User("길원", "pw"); // 유저 정보
        userRepository.save(user); // 유저 저장
        // When
        userRepository.delete(user); // 유저 삭제
        User findUser = userRepository.findById(1L).orElse(null); // 삭제 확인
        // then
        Assertions.assertThat(findUser).isNull(); // 없어야 함.
    }
}
