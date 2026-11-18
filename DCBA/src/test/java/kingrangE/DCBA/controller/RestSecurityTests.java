package kingrangE.DCBA.controller;
import kingrangE.DCBA.config.SecurityConfig;
import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.service.ExerciseService;
import kingrangE.DCBA.service.GenerationAdminService;
import kingrangE.DCBA.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.web.csrf.CsrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers = {UserController.class, AdminController.class}, properties = "DCBA_ADMIN_USER_ID=17")
@Import({SecurityConfig.class, GenerationAdminService.class})
class RestSecurityTests {
 @Autowired MockMvc mvc;
 @MockitoBean UserService users;
 @MockitoBean ExerciseService exercises;
 @MockitoBean StringRedisTemplate redis;
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

 @Test void onlyAdministratorCanReadOrPauseAutomaticGeneration() throws Exception {
  var path = "/api/admin/automatic-generation/pause";
  var anonymous = new MockHttpSession();
  var anonymousToken = csrf(anonymous);
  mvc.perform(get(path).session(anonymous)).andExpect(status().isUnauthorized());
  mvc.perform(put(path).session(anonymous).header(anonymousToken.getHeaderName(), anonymousToken.getToken()))
          .andExpect(status().isUnauthorized());

  var regular = new MockHttpSession();
  regular.setAttribute("loginUser", new User(18L, "reader", "hash", null, null));
  var regularToken = csrf(regular);
  mvc.perform(get(path).session(regular)).andExpect(status().isForbidden());
  mvc.perform(put(path).session(regular).header(regularToken.getHeaderName(), regularToken.getToken()))
          .andExpect(status().isForbidden());
  verifyNoInteractions(redis);

  var admin = new MockHttpSession();
  var adminUser = new User(17L, "operator", "hash", null, null);
  admin.setAttribute("loginUser", adminUser);
  when(users.getUser(17L)).thenReturn(adminUser);
  mvc.perform(get("/api/auth/me").session(admin)).andExpect(jsonPath("$.admin").value(true));
  mvc.perform(put(path).session(admin)).andExpect(status().isForbidden());
  verifyNoInteractions(redis);

  var adminToken = csrf(admin);
  var key = "exercise:auto_generation_paused";
  when(redis.hasKey(key)).thenReturn(false, true);
  @SuppressWarnings("unchecked")
  ValueOperations<String, String> values = mock(ValueOperations.class);
  when(redis.opsForValue()).thenReturn(values);
  mvc.perform(get(path).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(false));
  mvc.perform(put(path).session(admin).header(adminToken.getHeaderName(), adminToken.getToken()))
          .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
  verify(values).set(key, "1");
  mvc.perform(get(path).session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
 }

 private CsrfToken csrf(MockHttpSession session) throws Exception {
  return (CsrfToken) mvc.perform(get("/api/auth/csrf").session(session))
          .andExpect(status().isOk()).andReturn().getRequest().getAttribute("_csrf");
 }

 @Test void onlyAdministratorCanListOrDeletePoolProblems() throws Exception {
  var path = "/api/admin/exercises";
  var anonymous = new MockHttpSession();
  var anonymousToken = csrf(anonymous);
  mvc.perform(get(path).session(anonymous)).andExpect(status().isUnauthorized());
  mvc.perform(delete(path + "/45").session(anonymous).header(anonymousToken.getHeaderName(), anonymousToken.getToken()))
          .andExpect(status().isUnauthorized());

  var regular = new MockHttpSession();
  regular.setAttribute("loginUser", new User(18L, "reader", "hash", null, null));
  var regularToken = csrf(regular);
  mvc.perform(get(path).session(regular)).andExpect(status().isForbidden());
  mvc.perform(delete(path + "/45").session(regular).header(regularToken.getHeaderName(), regularToken.getToken()))
          .andExpect(status().isForbidden());

  var admin = new MockHttpSession();
  admin.setAttribute("loginUser", new User(17L, "operator", "hash", null, null));
  mvc.perform(delete(path + "/45").session(admin)).andExpect(status().isForbidden());
  verifyNoInteractions(exercises);

  when(exercises.getExercisePool(46L, null)).thenReturn(new SliceImpl<>(java.util.List.of()));
  mvc.perform(get(path).param("before", "46").session(admin))
          .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
  var adminToken = csrf(admin);
  mvc.perform(delete(path + "/45").session(admin).header(adminToken.getHeaderName(), adminToken.getToken()))
          .andExpect(status().isNoContent());
  verify(exercises).deleteExerciseFromPool(45L);
 }
}
