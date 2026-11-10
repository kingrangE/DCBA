package kingrangE.DCBA.controller;

import kingrangE.DCBA.config.SecurityConfig;
import kingrangE.DCBA.domain.*;
import kingrangE.DCBA.service.ExerciseService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExerciseController.class)
@Import(SecurityConfig.class)
class DashboardTests {
    @Autowired MockMvc mvc;
    @MockitoBean ExerciseService service;

    @org.junit.jupiter.api.Test
    void missingExerciseReturnsNotFoundWithoutInternalDetails() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User("reader", "unused"));
        var token = (org.springframework.security.web.csrf.CsrfToken) mvc.perform(get("/js/render-exercises.js").session(session))
                .andReturn().getRequest().getAttribute("_csrf");
        doThrow(new jakarta.persistence.EntityNotFoundException("private resource detail"))
                .when(service).saveExercise(any(), eq(999L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/exercise/save")
                        .session(session).param(token.getParameterName(), token.getToken()).param("exerciseId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("private resource detail"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"selected", "banned"})
    void paginationAndFiltersStayInCurrentList(String view) throws Exception {
        User user = new User(1L, "reader", "unused", null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", user);
        Exercise exercise = Exercise.builder().id(1L).subject(Subject.ALGORITHM)
                .level(Level.EASY).question("Question").answer("Answer").build();
        Page<Exercise> page = new PageImpl<>(List.of(exercise), PageRequest.of(0, 9), 20);
        when(service.getSelectedExercises(eq(1L), eq(Subject.ALGORITHM), eq(Level.EASY), any())).thenReturn(page);
        when(service.getBannedExercises(eq(1L), eq(Subject.ALGORITHM), eq(Level.EASY), any())).thenReturn(page);
        mvc.perform(get("/dashboard/" + view).session(session).param("subject", "ALGORITHM").param("level", "EASY"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/dashboard/" + view + "\"")))
                .andExpect(content().string(containsString("/dashboard/" + view + "?page=1")))
                .andExpect(content().string(containsString("subject=ALGORITHM")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
