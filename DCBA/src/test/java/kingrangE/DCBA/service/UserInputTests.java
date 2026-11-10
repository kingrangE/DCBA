package kingrangE.DCBA.service;

import kingrangE.DCBA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserInputTests {
    @Test
    void rejectsInvalidSignupBeforeDatabaseOrHashing() {
        UserRepository repository = mock(UserRepository.class);
        UserService service = new UserService(repository);
        for (String name : new String[]{null, "", "   ", " reader", "reader ", "abcdefghijk"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.signUp(name, "password"));
        }
        for (String password : new String[]{null, "", "   ", "a".repeat(73), "가".repeat(25)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.signUp("reader", password));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsNameAndMultibytePasswordAtTheirLimits() {
        UserRepository repository = mock(UserRepository.class);
        new UserService(repository).signUp("가나다라마바사아자차", "가".repeat(24));
        verify(repository).save(argThat(user -> user.getName().equals("가나다라마바사아자차")
                && org.springframework.security.crypto.bcrypt.BCrypt.checkpw("가".repeat(24), user.getPassword())));
    }
}
