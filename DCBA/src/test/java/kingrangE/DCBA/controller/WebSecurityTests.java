package kingrangE.DCBA.controller;

import kingrangE.DCBA.config.SecurityConfig;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.service.UserService;
import kingrangE.DCBA.service.UserRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.security.web.csrf.CsrfToken;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class WebSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserService userService;

    @Test
    void successfulLoginRotatesSessionIdAndPreservesSessionAttributes() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("existingAttribute", "preserved");
        CsrfToken token = (CsrfToken) mvc.perform(get("/").session(session)).andReturn().getRequest().getAttribute("_csrf");
        String originalSessionId = session.getId();
        User user = new User("reader", "hashed-password");
        when(userService.login("reader", "password")).thenReturn(user);

        mvc.perform(post("/login").session(session)
                        .param(token.getParameterName(), token.getToken())
                        .param("name", "reader").param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(request().sessionAttribute("loginUser", user))
                .andExpect(request().sessionAttribute("existingAttribute", "preserved"));

        assertThat(session.getId()).isNotEqualTo(originalSessionId);
    }

    @Test
    void failedLoginDoesNotRotateSessionOrSignIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfToken token = (CsrfToken) mvc.perform(get("/").session(session)).andReturn().getRequest().getAttribute("_csrf");
        String originalSessionId = session.getId();
        when(userService.login("reader", "wrong")).thenThrow(new UserRequestException("Invalid credentials"));

        mvc.perform(post("/login").session(session)
                        .param(token.getParameterName(), token.getToken())
                        .param("name", "reader").param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?error"))
                .andExpect(request().sessionAttributeDoesNotExist("loginUser"));

        assertThat(session.getId()).isEqualTo(originalSessionId);
    }

    @Test
    void signupDatabaseFailureReturnsServerErrorWithoutInternalDetails() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfToken token = (CsrfToken) mvc.perform(get("/").session(session)).andReturn().getRequest().getAttribute("_csrf");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("private SQL details"))
                .when(userService).signUp("reader", "password");
        mvc.perform(post("/signup").session(session)
                        .param(token.getParameterName(), token.getToken())
                        .param("name", "reader").param("password", "password"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL details"))));
    }

    @Test
    void signupValidationFailureUsesRequestPathWithoutReferer() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfToken token = (CsrfToken) mvc.perform(get("/").session(session)).andReturn().getRequest().getAttribute("_csrf");
        org.mockito.Mockito.doThrow(new UserRequestException("Invalid nickname"))
                .when(userService).signUp("reader", "password");
        mvc.perform(post("/signup").session(session)
                        .param(token.getParameterName(), token.getToken())
                        .param("name", "reader").param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signup?error"))
                .andExpect(flash().attribute("errorMessage", "Invalid nickname"));
    }

    @Test
    void logoutInvalidatesLoginSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", "signed-in-user");
        CsrfToken token = (CsrfToken) mvc.perform(get("/").session(session)).andReturn().getRequest().getAttribute("_csrf");
        mvc.perform(post("/logout").session(session).param(token.getParameterName(), token.getToken()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().maxAge("JSESSIONID", 0));
        assertThat(session.isInvalid()).isTrue();
    }
    @Test
    void rejectsStateChangesWithoutCsrfToken() throws Exception {
        for (String path : new String[]{"/login", "/signup", "/logout", "/exercise/save", "/exercise/ban"}) {
            mvc.perform(post(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    void loginFormContainsCsrfToken() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
    }
}
