package kingrangE.DCBA.service;

import kingrangE.DCBA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserInputTests {
    @Test
    void concurrentNameConflictBecomesUserError() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByName("reader")).thenReturn(java.util.Optional.empty(),
                java.util.Optional.of(new kingrangE.DCBA.domain.User("reader", "hash")));
        when(repository.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> new UserService(repository).signUp("reader", "password"))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("해당 닉네임을 사용하는 유저가 존재합니다.");
    }

    @Test
    void unrelatedDatabaseConstraintFailureStillPropagates() {
        UserRepository repository = mock(UserRepository.class);
        var failure = new org.springframework.dao.DataIntegrityViolationException("unexpected constraint");
        when(repository.save(any())).thenThrow(failure);
        assertThatThrownBy(() -> new UserService(repository).signUp("reader", "password"))
                .isSameAs(failure);
    }

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
