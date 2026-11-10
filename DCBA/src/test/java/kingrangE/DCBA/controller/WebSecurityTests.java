package kingrangE.DCBA.controller;

import kingrangE.DCBA.config.SecurityConfig;
import kingrangE.DCBA.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
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
