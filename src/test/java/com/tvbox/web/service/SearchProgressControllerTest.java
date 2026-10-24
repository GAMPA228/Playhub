package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tvbox.web.controller.GlobalExceptionHandler;
import com.tvbox.web.controller.TvboxController;
import com.tvbox.web.model.SiteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchProgressControllerTest {

    private final ProgressTrackerService tracker = new ProgressTrackerService();
    private final TvboxFacadeService facade = mock(TvboxFacadeService.class);
    private final MockHttpSession session = new MockHttpSession();
    private final SourceSettingsService sourceSettings = mock(SourceSettingsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(sourceSettings.getSettings()).thenReturn(new SourceSettingsService.Settings(
                "https://config.example/saved", List.of("https://config.example/saved"), "", "", true));
        mvc = MockMvcBuilders.standaloneSetup(new TvboxController(facade, tracker, sourceSettings))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void progressSnapshotsExposePartialResultsAndRemainReadableAfterCompletion() throws Exception {
        String id = tracker.createSearchProgress(2, session.getId());
        ProgressTrackerService.ProgressInfo progress = tracker.getProgress(id);
        JsonNode row = JsonNodeFactory.instance.objectNode().put("vod_id", "1");
        progress.completeSource(List.of(row), false);

        mvc.perform(get("/api/progress/{id}", id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.results[0].vod_id").value("1"));
        progress.completeSource(List.of(), false);
        progress.setDone(true);
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/progress/{id}", id).session(session))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.found").value(true))
                    .andExpect(jsonPath("$.done").value(true))
                    .andExpect(jsonPath("$.results[0].vod_id").value("1"));
        }
    }

    @Test
    void anotherSessionCannotPollOrCancelSearch() throws Exception {
        String id = tracker.createSearchProgress(77, session.getId());
        MockHttpSession another = new MockHttpSession();

        mvc.perform(get("/api/progress/{id}", id).session(another))
                .andExpect(status().isOk()).andExpect(jsonPath("$.found").value(false));
        mvc.perform(post("/api/search/{id}/cancel", id).session(another))
                .andExpect(status().isOk()).andExpect(jsonPath("$.found").value(false));
        assertFalse(tracker.getProgress(id).isCancelled());

        mvc.perform(post("/api/search/{id}/cancel", id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.cancelled").value(true))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void newSearchCancelsOnlyThePreviousSearchOwnedByTheSameSession() {
        AtomicInteger cancelled = new AtomicInteger();
        String oldId = tracker.createSearchProgress(77, session.getId());
        tracker.getProgress(oldId).addCancelHook(cancelled::incrementAndGet);
        String otherId = tracker.createSearchProgress(77, "another-browser");

        String newId = tracker.createSearchProgress(1, session.getId());

        assertEquals(1, cancelled.get());
        assertTrue(tracker.getProgress(oldId).isCancelled());
        assertFalse(tracker.getProgress(otherId).isCancelled());
        assertFalse(tracker.getProgress(newId).isDone());
    }

    @Test
    void scopedSearchUsesTheRequestedSourceAndEmptyResultsStillFinish() throws Exception {
        SiteDefinition site = new SiteDefinition();
        site.setUid("s17");
        when(facade.searchableSources(session.getId(), "s17")).thenReturn(List.of(site));
        doAnswer(invocation -> {
            ProgressTrackerService.ProgressInfo progress = invocation.getArgument(4);
            progress.completeSource(List.of(), false);
            progress.setDone(true);
            return null;
        }).when(facade).searchAllBackground(anyString(), anyString(), anyBoolean(), any(), any());

        String response = mvc.perform(get("/api/search/all").param("wd", "测试").param("key", "s17")
                        .session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("progressId").asText();

        mvc.perform(get("/api/progress/{id}", id).session(session))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void expiredCompletedResultsAreRemovedWithoutAffectingActiveSearches() {
        String id = tracker.createSearchProgress(0, session.getId());
        ProgressTrackerService.ProgressInfo progress = tracker.getProgress(id);
        progress.setDone(true);
        ReflectionTestUtils.setField(progress, "finishedAt", System.currentTimeMillis() - 301_000L);

        assertEquals(null, tracker.getProgress(id, session.getId()));
        String active = tracker.createSearchProgress(77, session.getId());
        assertFalse(tracker.getProgress(active, session.getId()).isDone());
    }

    @Test
    void successfulConfigReplacementCancelsOldSearchButFailurePreservesIt() throws Exception {
        String id = tracker.createSearchProgress(77, session.getId());
        when(facade.load(session.getId(), "https://config.example/fail", true))
                .thenThrow(new IllegalStateException("配置不可用"));
        when(facade.load(session.getId(), "https://config.example/success", true))
                .thenReturn(new com.tvbox.web.model.ConfigPayload());

        mvc.perform(post("/api/config/load").session(session).contentType("application/json")
                        .content("{\"url\":\"https://config.example/fail\"}"))
                .andExpect(status().isBadRequest());
        assertFalse(tracker.getProgress(id).isCancelled());

        mvc.perform(post("/api/config/load").session(session).contentType("application/json")
                        .content("{\"url\":\"https://config.example/success\"}"))
                .andExpect(status().isOk());
        assertTrue(tracker.getProgress(id).isCancelled());
    }

    @Test
    void bootstrapExplicitlyDisablesPersistenceAndReturnsTheSharedSnapshot() throws Exception {
        String url = "https://config.example/restore";
        when(facade.load(session.getId(), url, false)).thenReturn(new com.tvbox.web.model.ConfigPayload());

        mvc.perform(post("/api/config/load").session(session).contentType("application/json")
                        .content("{\"url\":\"" + url + "\",\"persist\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceSettings.configUrl").value("https://config.example/saved"))
                .andExpect(jsonPath("$.sourceSettings.initialized").value(true));

        verify(facade).load(session.getId(), url, false);
    }
}
