package kingrangE.DCBA.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.dto.LoginForm;
import kingrangE.DCBA.dto.api.UserResponse;
import kingrangE.DCBA.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService);
    }

    @Test
    void loginStoresUserInSession() {
        LoginForm form = new LoginForm();
        form.setName("tester");
        form.setPassword("password");
        User user = new User("tester", "encoded-password");
        MockHttpSession session = new MockHttpSession();
        when(userService.login("tester", "password")).thenReturn(user);

        UserResponse response = controller.login(form, session);

        assertThat(response.name()).isEqualTo("tester");
        assertThat(session.getAttribute("loginUser")).isSameAs(user);
    }

    @Test
    void logoutInvalidatesSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User("tester", "password"));

        assertThat(controller.logout(session).getStatusCode().value()).isEqualTo(204);
        assertThat(session.isInvalid()).isTrue();
        verify(userService, org.mockito.Mockito.never()).getUser(org.mockito.ArgumentMatchers.anyLong());
    }
}
