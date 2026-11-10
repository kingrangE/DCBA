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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"subject,UNKNOWN", "level,UNKNOWN"})
    void invalidFilterReturnsBadRequestWithoutCallingService(String field, String value) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User("reader", "unused"));
        mvc.perform(get("/dashboard").session(session).param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("UNKNOWN"))));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "selected", "banned"})
    void listMembershipSuppliesKnownFlagsWithoutAnotherLookup(String view) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User(1L, "reader", "unused", null));
        Slice<Exercise> page = new SliceImpl<>(List.of(Exercise.builder().id(1L).subject(Subject.ALGORITHM)
                .level(Level.EASY).question("Question").answer("Answer").build()), PageRequest.of(0, 9), false);
        when(service.getExercises(eq(1L), any(), any(), any(), any())).thenReturn(page);
        when(service.getSelectedExercises(eq(1L), any(), any(), any(), any())).thenReturn(page);
        when(service.getBannedExercises(eq(1L), any(), any(), any(), any())).thenReturn(page);
        mvc.perform(get(view.equals("all") ? "/dashboard" : "/dashboard/" + view).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("savedIds", view.equals("selected") ? List.of(1L) : List.of()))
                .andExpect(model().attribute("bannedIds", view.equals("banned") ? List.of(1L) : List.of()));
        if (view.equals("selected")) {
            verify(service, never()).getSavedExerciseIds(any(), any());
            verify(service).getBannedExerciseIds(1L, List.of(1L));
        } else {
            verify(service, never()).getBannedExerciseIds(any(), any());
            verify(service).getSavedExerciseIds(1L, List.of(1L));
        }
    }

    @org.junit.jupiter.api.Test
    void invalidCursorKeepsBadRequestStatus() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User(1L, "reader", "unused", null));
        when(service.getExercises(1L, null, null, 0L, null))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST));
        mvc.perform(get("/dashboard").session(session).param("before", "0"))
                .andExpect(status().isBadRequest());
    }

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

    @org.junit.jupiter.api.Test
    void backwardSliceIsDisplayedNewestFirstAndUsesDirectionCursors() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User(1L, "reader", "unused", null));
        List<Exercise> ascending = java.util.stream.LongStream.of(11, 12).mapToObj(id -> Exercise.builder()
                .id(id).subject(Subject.ALGORITHM).level(Level.EASY).question("Question").answer("Answer").build()).toList();
        when(service.getExercises(1L, null, null, null, 10L))
                .thenReturn(new SliceImpl<>(ascending, PageRequest.of(0, 9), false));
        mvc.perform(get("/dashboard").session(session).param("after", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("exercises", ascending.reversed()))
                .andExpect(model().attribute("hasPrevious", false))
                .andExpect(model().attribute("hasNext", true))
                .andExpect(content().string(containsString("/dashboard?before=11")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("page="))));
    }

    @org.junit.jupiter.api.Test
    void emptyCursorResultStillOffersFirstPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", new User(1L, "reader", "unused", null));
        when(service.getExercises(1L, null, null, 1L, null))
                .thenReturn(new SliceImpl<>(List.of()));
        mvc.perform(get("/dashboard").session(session).param("before", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">First</a>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("before="))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"selected", "banned"})
    void paginationAndFiltersStayInCurrentList(String view) throws Exception {
        User user = new User(1L, "reader", "unused", null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUser", user);
        Exercise exercise = Exercise.builder().id(1L).subject(Subject.ALGORITHM)
                .level(Level.EASY).question("Question").answer("Answer").build();
        Slice<Exercise> page = new SliceImpl<>(List.of(exercise), PageRequest.of(0, 9), true);
        when(service.getSelectedExercises(eq(1L), eq(Subject.ALGORITHM), eq(Level.EASY), any(), any())).thenReturn(page);
        when(service.getBannedExercises(eq(1L), eq(Subject.ALGORITHM), eq(Level.EASY), any(), any())).thenReturn(page);
        mvc.perform(get("/dashboard/" + view).session(session).param("subject", "ALGORITHM").param("level", "EASY"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/dashboard/" + view + "\"")))
                .andExpect(content().string(containsString("/dashboard/" + view + "?before=1")))
                .andExpect(content().string(containsString("subject=ALGORITHM")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
