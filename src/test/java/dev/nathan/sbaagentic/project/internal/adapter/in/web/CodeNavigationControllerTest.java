package dev.nathan.sbaagentic.project.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.project.CodeNavigationOperations;
import dev.nathan.sbaagentic.project.CodeNavigationResult;
import dev.nathan.sbaagentic.project.CodeProjectScope;
import dev.nathan.sbaagentic.project.CodeReference;
import dev.nathan.sbaagentic.project.ProjectGraphOperations;
import dev.nathan.sbaagentic.project.ProjectMeldOperations;
import dev.nathan.sbaagentic.project.ProjectOperations;
import dev.nathan.sbaagentic.project.internal.application.CodeNavigationError;
import dev.nathan.sbaagentic.project.internal.application.CodeNavigationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodeNavigationControllerTest {

    private CodeNavigationOperations navigation;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        navigation = mock(CodeNavigationOperations.class);
        ProjectController controller = new ProjectController(
                mock(ProjectOperations.class),
                mock(ProjectMeldOperations.class),
                mock(ProjectGraphOperations.class),
                navigation);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsEligibleScopesAndActionSuccessBodies() throws Exception {
        when(navigation.codeScopes()).thenReturn(List.of(new CodeProjectScope("key", "/repo")));
        when(navigation.openInEditor(any(CodeReference.class))).thenReturn(new CodeNavigationResult("opened"));
        when(navigation.revealInFinder(any(CodeReference.class))).thenReturn(new CodeNavigationResult("revealed"));

        mockMvc.perform(get("/api/projects/code-scopes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectKey").value("key"))
                .andExpect(jsonPath("$[0].root").value("/repo"));
        mockMvc.perform(post("/api/open-in-editor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"key","relativePath":"src/App.ts","line":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("opened"));
        mockMvc.perform(post("/api/reveal-in-finder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"key","relativePath":"src/App.ts"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revealed"));
    }

    @Test
    void mapsNavigationFailuresToStableTypedEnvelopes() throws Exception {
        when(navigation.openInEditor(any(CodeReference.class)))
                .thenThrow(new CodeNavigationException(
                        CodeNavigationError.OUTSIDE_PROJECT_ROOT,
                        "The file reference leaves its known project root."));

        mockMvc.perform(post("/api/open-in-editor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"key","relativePath":"../secret"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.status").value(403))
                .andExpect(jsonPath("$.error.type").value("outside_project_root"))
                .andExpect(jsonPath("$.error.message")
                        .value("The file reference leaves its known project root."));
    }
}
