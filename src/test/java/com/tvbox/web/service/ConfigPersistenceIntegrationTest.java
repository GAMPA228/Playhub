package com.tvbox.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tvbox.web.controller.GlobalExceptionHandler;
import com.tvbox.web.controller.SourceSettingsController;
import com.tvbox.web.controller.TvboxController;
import com.tvbox.web.model.ConfigPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigPersistenceIntegrationTest {

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger downloads = new AtomicInteger();
    private HttpServer server;
    private String base;
    private SourceSettingsService settings;
    private ConfigService config;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            downloads.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/broken.json") ? "{\"sites\":[{},null]}"
                    : "{\"sites\":[{\"key\":\"" + (path.equals("/two.json") ? "two" : "one")
                    + "\",\"name\":\"测试站点\",\"type\":1,\"api\":\"./api\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(path.equals("/offline.json") ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        recreateApplication();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void editsThroughTheRealApiSurviveRestartAndRestoreInAnotherBrowser() throws Exception {
        MockHttpSession browser = new MockHttpSession();
        mvc.perform(post("/api/source-settings").session(browser).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "liveSourceUrl", base + "/live.m3u", "liveEpgUrl", base + "/epg.xml"))))
                .andExpect(status().isOk());
        load(browser, "/one.json", true);
        assertEquals(1, downloads.get());
        assertTrue(Files.exists(directory.resolve("source-settings.json")));

        recreateApplication();
        assertEquals(1, downloads.get(), "Starting the service must not download remote configuration");
        MockHttpSession anotherBrowser = new MockHttpSession();
        mvc.perform(get("/api/source-settings").session(anotherBrowser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configUrl").value(base + "/one.json"))
                .andExpect(jsonPath("$.liveSourceUrl").value(base + "/live.m3u"))
                .andExpect(jsonPath("$.liveEpgUrl").value(base + "/epg.xml"));
        mvc.perform(get("/api/config").session(anotherBrowser))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sites[0].key").value("one"));
        assertEquals(2, downloads.get());
        assertEquals(List.of(base + "/one.json"), settings.getSettings().configHistory());
    }

    @Test
    void failedReplacementPreservesTheDiskAndDoesNotPoisonAnotherBrowsersRestore() throws Exception {
        MockHttpSession browser = new MockHttpSession();
        load(browser, "/one.json", true);
        byte[] saved = Files.readAllBytes(directory.resolve("source-settings.json"));
        ConfigPayload previous = config.getPayload(browser.getId());

        for (String failed : List.of("/broken.json", "/offline.json")) {
            mvc.perform(post("/api/config/load").session(browser).contentType("application/json")
                            .content(mapper.writeValueAsString(Map.of("url", base + failed))))
                    .andExpect(status().isBadRequest());
            assertEquals(new String(saved, StandardCharsets.UTF_8),
                    Files.readString(directory.resolve("source-settings.json")));
            assertSame(previous, config.getPayload(browser.getId()));
            assertSame(previous.getSites().get(0), config.getSite(browser.getId(), "s0").orElseThrow());
        }

        mvc.perform(get("/api/config").session(new MockHttpSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sites[0].key").value("one"));
        assertEquals(List.of(base + "/one.json"), settings.getSettings().configHistory());
    }

    @Test
    void changingSharedSettingsDoesNotInvalidateAnotherBrowsersInFlightSiteIds() throws Exception {
        MockHttpSession first = new MockHttpSession();
        MockHttpSession second = new MockHttpSession();
        load(first, "/one.json", true);
        ConfigPayload previous = config.getPayload(first.getId());
        load(second, "/two.json", true);

        mvc.perform(get("/api/source-settings").session(first))
                .andExpect(jsonPath("$.configUrl").value(base + "/two.json"));
        assertSame(previous, config.getPayload(first.getId()));
        assertEquals("one", config.getSite(first.getId(), "s0").orElseThrow().getKey());
        assertEquals("two", config.getSite(second.getId(), "s0").orElseThrow().getKey());
        mvc.perform(get("/api/config").session(new MockHttpSession()))
                .andExpect(jsonPath("$.sites[0].key").value("two"));
    }

    @Test
    void aStaleBootstrapCanRestoreItsSessionWithoutReplacingSharedSettings() throws Exception {
        MockHttpSession first = new MockHttpSession();
        load(first, "/one.json", true);
        load(first, "/two.json", true);
        MockHttpSession staleBrowser = new MockHttpSession();

        mvc.perform(post("/api/config/load").session(staleBrowser).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("url", base + "/one.json", "persist", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.sites[0].key").value("one"))
                .andExpect(jsonPath("$.sourceSettings.configUrl").value(base + "/two.json"));

        assertEquals(base + "/two.json", settings.getSettings().configUrl());
        assertEquals(List.of(base + "/two.json", base + "/one.json"), settings.getSettings().configHistory());
    }

    private void load(MockHttpSession session, String path, boolean persist) throws Exception {
        mvc.perform(post("/api/config/load").session(session).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("url", base + path, "persist", persist))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }

    private void recreateApplication() throws Exception {
        settings = new SourceSettingsService(mapper, mock(AppPathsService.class));
        ReflectionTestUtils.setField(settings, "dataDir", directory.toString());
        settings.init();
        config = new ConfigService(mapper, mock(AppPathsService.class), settings);
        TvboxFacadeService facade = new TvboxFacadeService(config, mock(HttpSourceService.class), mock(JarSpiderService.class));
        mvc = MockMvcBuilders.standaloneSetup(
                        new TvboxController(facade, new ProgressTrackerService(), settings),
                        new SourceSettingsController(settings))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
