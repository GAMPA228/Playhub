package com.tvbox.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tvbox.web.controller.GlobalExceptionHandler;
import com.tvbox.web.controller.SourceSettingsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SourceSettingsServiceTest {

    @TempDir
    Path data;

    private final ObjectMapper mapper = new ObjectMapper();
    private SourceSettingsService service;

    @BeforeEach
    void setUp() throws Exception {
        service = create(mapper);
    }

    @Test
    void missingFileIsAnUninitializedEmptyConfiguration() {
        SourceSettingsService.Settings settings = service.getSettings();
        assertFalse(settings.initialized());
        assertEquals("", settings.configUrl());
        assertTrue(settings.configHistory().isEmpty());
        assertFalse(Files.exists(data.resolve("source-settings.json")));
    }

    @Test
    void unicodeAddressesPersistAcrossServiceRestartWithoutBom() throws Exception {
        SourceSettingsService.Settings saved = service.update(json("""
                {"configUrl":"https://饭太硬.top/配置.json;pk;key",
                 "liveSourceUrl":"https://直播.example/频道.m3u",
                 "liveEpgUrl":"https://epg.example/节目.xml"}
                """));
        assertEquals(saved, create(mapper).getSettings());
        byte[] bytes = Files.readAllBytes(data.resolve("source-settings.json"));
        assertEquals('{', bytes[0]);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("配置"));
        assertTrue(saved.initialized());
    }

    @Test
    void videoSaveMaintainsBoundedRecentHistoryAndDoesNotOverwriteLiveSettings() throws Exception {
        service.update(json("{\"liveSourceUrl\":\"https://live.example/tv.m3u\"}"));
        for (int i = 0; i < 12; i++) {
            service.saveVideoConfig("https://config.example/" + i);
        }
        SourceSettingsService.Settings saved = service.saveVideoConfig(" https://config.example/7 ");
        assertEquals(10, saved.configHistory().size());
        assertEquals("https://config.example/7", saved.configHistory().get(0));
        assertEquals(1, saved.configHistory().stream().filter(url -> url.endsWith("/7")).count());
        assertEquals("https://live.example/tv.m3u", saved.liveSourceUrl());
        assertThrows(UnsupportedOperationException.class, () -> saved.configHistory().add("x"));
    }

    @Test
    void independentConcurrentPatchesPreserveBothChanges() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> video = executor.submit(() -> {
                start.await();
                service.saveVideoConfig("https://config.example/shared.json");
                return null;
            });
            Future<?> live = executor.submit(() -> {
                start.await();
                service.update(json("{\"liveSourceUrl\":\"https://live.example/shared.m3u\"}"));
                return null;
            });
            start.countDown();
            video.get(3, TimeUnit.SECONDS);
            live.get(3, TimeUnit.SECONDS);
            SourceSettingsService.Settings settings = create(mapper).getSettings();
            assertEquals("https://config.example/shared.json", settings.configUrl());
            assertEquals("https://live.example/shared.m3u", settings.liveSourceUrl());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clearingAllAddressesRemainsInitializedAfterRestart() throws Exception {
        service.saveVideoConfig("https://config.example/a");
        service.update(json("{\"liveSourceUrl\":\"https://live.example/a.m3u\"}"));
        service.update(json("{\"configUrl\":\"\",\"configHistory\":[],\"liveSourceUrl\":\"\",\"liveEpgUrl\":\"\"}"));
        SourceSettingsService.Settings empty = create(mapper).getSettings();
        assertTrue(empty.initialized());
        assertEquals("", empty.configUrl());
        assertEquals("", empty.liveSourceUrl());
        assertTrue(empty.configHistory().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{\"liveSourceUrl\":null}", "{\"liveEpgUrl\":17}",
            "{\"liveSourceUrl\":\"file:///etc/passwd\"}",
            "{\"configUrl\":\"javascript:alert(1)\"}",
            "{\"configUrl\":\"https://bad host/config\"}",
            "{\"configHistory\":{}}", "{\"configHistory\":[null]}",
            "{\"aiKey\":\"must-not-be-shared\"}",
            "{\"configUrl\":\"https://config.example/a\\nheader\"}"
    })
    void invalidPatchesNeverChangeTheSavedFile(String input) throws Exception {
        SourceSettingsService.Settings before = service.saveVideoConfig("https://config.example/good");
        String fileBefore = Files.readString(data.resolve("source-settings.json"));
        assertThrows(IllegalArgumentException.class, () -> service.update(json(input)));
        assertEquals(before, service.getSettings());
        assertEquals(fileBefore, Files.readString(data.resolve("source-settings.json")));
    }

    @Test
    void oversizedUrlsAndHistoriesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.update(mapper.createObjectNode()
                .put("liveSourceUrl", "https://live.example/" + "a".repeat(8192))));
        JsonNode history = mapper.valueToTree(java.util.Collections.nCopies(11, "https://config.example/a"));
        assertThrows(IllegalArgumentException.class, () -> service.update(mapper.createObjectNode().set("configHistory", history)));
        assertFalse(service.getSettings().initialized());
    }

    @Test
    void corruptOrOversizedStoredFilesAreNotSilentlyOverwrittenAndCanBeRepaired() throws Exception {
        Path file = data.resolve("source-settings.json");
        Files.writeString(file, "not valid json");
        assertThrows(IllegalStateException.class, service::getSettings);
        assertThrows(IllegalStateException.class, () -> service.update(json("{}")));
        assertEquals("not valid json", Files.readString(file));
        Files.write(file, new byte[512 * 1024 + 1]);
        assertThrows(IllegalStateException.class, service::getSettings);
        Files.writeString(file, "{\"configUrl\":\"https://config.example/repaired\"}");
        assertEquals("https://config.example/repaired", service.getSettings().configUrl());
    }

    @Test
    void failedWritePreservesThePreviousConfiguration() throws Exception {
        SourceSettingsService.Settings previous = service.saveVideoConfig("https://config.example/good");
        ObjectMapper failingMapper = spy(new ObjectMapper());
        ObjectWriter writer = mock(ObjectWriter.class);
        doReturn(writer).when(failingMapper).writerWithDefaultPrettyPrinter();
        when(writer.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("simulated disk write failure") {
        });
        SourceSettingsService failing = create(failingMapper);
        assertThrows(IllegalStateException.class, () -> failing.saveVideoConfig("https://config.example/new"));
        assertEquals(previous, service.getSettings());
        try (var files = Files.list(data)) {
            assertEquals(List.of("source-settings.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void allBrowserSessionsShareEditableSettingsAndResponsesAreNotCached() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceSettingsController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        MockHttpSession desktop = new MockHttpSession();
        MockHttpSession phone = new MockHttpSession();
        mvc.perform(post("/api/source-settings").session(desktop).contentType("application/json")
                        .content("{\"liveSourceUrl\":\"https://live.example/one.m3u\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.initialized").value(true));
        mvc.perform(get("/api/source-settings").session(phone))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.liveSourceUrl").value("https://live.example/one.m3u"));
        mvc.perform(post("/api/source-settings").session(phone).contentType("application/json")
                        .content("{\"liveSourceUrl\":\"\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/source-settings").session(desktop))
                .andExpect(jsonPath("$.liveSourceUrl").value(""));
        mvc.perform(post("/api/source-settings").session(phone).contentType("application/json")
                        .content("{\"liveSourceUrl\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
    }

    private JsonNode json(String input) throws Exception {
        return mapper.readTree(input);
    }

    private SourceSettingsService create(ObjectMapper objectMapper) throws Exception {
        SourceSettingsService settings = new SourceSettingsService(objectMapper, mock(AppPathsService.class));
        ReflectionTestUtils.setField(settings, "dataDir", data.toString());
        settings.init();
        return settings;
    }
}
