package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.repository.UserRepository;
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
public class UserServiceTest {
    UserService userService;
    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void setup() {
        userService = new UserService(userRepository);
    }

    @Test
    @DisplayName("회원가입 - 성공")
    void signUpSuccess() {
        // given
        // when
        userService.signUp("길원", "1");
        // then
        User findedUser = userRepository.findByName("길원").orElse(null); // 조회
        Assertions.assertThat(findedUser).isNotNull(); // 있으면 성공
    }

    @Test
    @DisplayName("회원가입 - ID 중복 실패")
    void signUpIdDuplicated() {
        // given
        userService.signUp("길원", "1");
        // when
        // then
        Assertions.assertThatThrownBy(() -> userService.signUp("길원1", "1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("해당 ID로 가입한 유저가 존재합니다.");
    }

    @Test
    @DisplayName("회원가입 - 닉네임 중복 실패")
    void signUpNameDuplicated() {
        // given
        userService.signUp("길원", "1");
        // when
        // then
        Assertions.assertThatThrownBy(() -> userService.signUp("길원", "1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("해당 닉네임을 사용하는 유저가 존재합니다.");
    }

    @Test
    @DisplayName("로그인 - 성공")
    void loginSuccess() {
        // given
        userService.signUp("길원", "1");
        // when
        User loginnedUser = userService.login("길원", "1");
        // then
        Assertions.assertThat(loginnedUser).isNotNull(); // 있으면 성공
    }

    @Test
    @DisplayName("로그인 - 존재하지 않는 닉네임")
    void loginNoSuchName() {
        // given
        userService.signUp("길원", "1");
        // when
        // then
        Assertions.assertThatThrownBy(() -> userService.login("길1", "1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("존재하지 않는 아이디입니다.");
    }

    @Test
    @DisplayName("로그인 - 비밀번호 틀림")
    void loginWrongPassword() {
        // given
        userService.signUp("길원", "1");
        // when
        // then
        Assertions.assertThatThrownBy(() -> userService.login("길원", "2"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("비밀번호가 틀립니다.");
    }
}
