package kingrangE.DCBA.controller;
import kingrangE.DCBA.config.SecurityConfig;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.web.csrf.CsrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class RestSecurityTests {
 @Autowired MockMvc mvc;
 @MockitoBean UserService users;
 @Test void loginRotatesSessionAndLogoutInvalidatesIt() throws Exception {
  var session = new MockHttpSession();
  var token = (CsrfToken) mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn().getRequest().getAttribute("_csrf");
  var id = session.getId();
  when(users.login("reader", "password")).thenReturn(new User("reader", "hash"));
  mvc.perform(post("/api/auth/login").session(session).header(token.getHeaderName(), token.getToken()).contentType("application/json").content("{\"name\":\"reader\",\"password\":\"password\"}")).andExpect(status().isOk());
  assertThat(session.getId()).isNotEqualTo(id);
  mvc.perform(post("/api/auth/logout").session(session).header(token.getHeaderName(), token.getToken())).andExpect(status().isNoContent());
  assertThat(session.isInvalid()).isTrue();
 }
 @Test void mutationsRequireCsrf() throws Exception {
  for (String path : new String[]{"/api/auth/login", "/api/auth/signup", "/api/auth/logout", "/api/exercises/1/saved", "/api/exercises/generation-requests"}) mvc.perform(post(path)).andExpect(status().isForbidden());
 }
 @Test void databaseFailureDoesNotLeakDetailsOrBecomeConflict() throws Exception {
  var session = new MockHttpSession();
  var token = (CsrfToken) mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getRequest().getAttribute("_csrf");
  doThrow(new org.springframework.dao.DataIntegrityViolationException("private SQL details")).when(users).signUp("reader", "password");
  mvc.perform(post("/api/auth/signup").session(session).header(token.getHeaderName(), token.getToken()).contentType("application/json").content("{\"name\":\"reader\",\"password\":\"password\"}")).andExpect(status().isInternalServerError()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL details"))));
 }
}
