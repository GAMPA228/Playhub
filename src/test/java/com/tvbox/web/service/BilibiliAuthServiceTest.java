package com.tvbox.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvbox.web.config.CorsConfig;
import com.tvbox.web.controller.BilibiliAuthController;
import com.tvbox.web.controller.GlobalExceptionHandler;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BilibiliAuthServiceTest {

    @TempDir
    Path data;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingFileUsesOptionalEnvironmentWithoutWritingIt() {
        BilibiliAuthService anonymous = auth("");
        assertEquals(new BilibiliAuthService.Status(false, "anonymous", null), anonymous.status());
        assertEquals("", anonymous.currentCookie());
        BilibiliAuthService configured = auth(" SESSDATA=environment-test ");
        assertEquals(new BilibiliAuthService.Status(true, "environment", null), configured.status());
        assertEquals("SESSDATA=environment-test", configured.currentCookie());
        assertFalse(Files.exists(file()));
    }

    @Test
    void savesReplacesAndClearsAcrossRestartsWithoutFallingBackToEnvironment() throws Exception {
        BilibiliAuthService service = auth("SESSDATA=environment-test");
        BilibiliAuthService.Status saved = service.update(" SESSDATA=first-test; bili_jct=x ");
        assertTrue(saved.configured());
        assertEquals("saved", saved.source());
        assertNotNull(saved.updatedAt());
        assertEquals(saved, auth("SESSDATA=environment-test").status());
        assertEquals("SESSDATA=first-test; bili_jct=x", auth("").currentCookie());
        service.update("SESSDATA=replacement-test");
        assertEquals("SESSDATA=replacement-test", auth("bad\nENV").currentCookie());
        service.update("");
        assertEquals("", service.currentCookie());
        assertEquals("", auth("SESSDATA=environment-test").currentCookie());
        assertFalse(auth("SESSDATA=environment-test").status().configured());
        assertEquals("saved", service.status().source());
        assertEquals('{', Files.readAllBytes(file())[0]);
        if (Files.getFileStore(data).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file()));
        }
    }

    @Test
    void invalidInputIsSanitizedAndCannotReplaceTheLastSavedValue() throws Exception {
        BilibiliAuthService service = auth("");
        service.update("SESSDATA=old-test");
        byte[] before = Files.readAllBytes(file());
        for (String raw : new String[]{null, "secret\nheader", "\rsecret", "private中文", "x".repeat(16385), "secret\u007f"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> service.update(raw));
            assertFalse(error.getMessage().contains("secret"));
            assertEquals("SESSDATA=old-test", service.currentCookie());
            assertArrayEquals(before, Files.readAllBytes(file()));
        }
    }

    @Test
    void writeFailurePreservesDiskAndPublishedCookieAndCleansTemporaryFiles() throws Exception {
        ObjectMapper failing = spy(new ObjectMapper());
        BilibiliAuthService service = new BilibiliAuthService(failing, data, "");
        service.update("SESSDATA=old-test");
        byte[] before = Files.readAllBytes(file());
        doThrow(new JsonProcessingException("must-not-leak-secret") { }).when(failing).writeValueAsBytes(any());
        var error = assertThrows(ResponseStatusException.class, () -> service.update("SESSDATA=new-test"));
        assertEquals(503, error.getStatusCode().value());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("must-not-leak"));
        assertEquals("SESSDATA=old-test", service.currentCookie());
        assertArrayEquals(before, Files.readAllBytes(file()));
        try (var files = Files.list(data)) {
            assertEquals(List.of(file()), files.toList());
        }
    }

    @Test
    void unreadableDirectoryDoesNotPublishTheReplacement() throws Exception {
        Path blocked = data.resolve("not-a-directory");
        Files.writeString(blocked, "file");
        BilibiliAuthService service = new BilibiliAuthService(mapper, blocked, "SESSDATA=environment-test");
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.update("SESSDATA=new-test")).getStatusCode().value());
        assertEquals("file", Files.readString(blocked));
    }

    @Test
    void corruptFileFailsClosedWithoutLeakingSecretsAndCanBeExplicitlyReplaced() throws Exception {
        for (String raw : List.of("{\"cookie\":private-secret", "{\"cookie\":123,\"updatedAt\":\"bad\"}",
                "{\"cookie\":\"test\",\"updatedAt\":\"invalid\"}", "x".repeat(65537))) {
            Files.writeString(file(), raw);
            BilibiliAuthService service = auth("SESSDATA=environment-test");
            var error = assertThrows(ResponseStatusException.class, service::status);
            assertEquals(503, error.getStatusCode().value());
            assertFalse(error.getMessage().contains("private-secret"));
            assertNull(error.getCause());
            assertEquals(raw, Files.readString(file()));
            service.update("SESSDATA=repaired-test");
            assertEquals("SESSDATA=repaired-test", auth("").currentCookie());
        }
    }

    @Test
    void invalidEnvironmentIsSanitizedAndCanBeOverriddenWithoutRestart() {
        BilibiliAuthService service = auth("SESSDATA=secret\nheader");
        assertEquals(503, assertThrows(ResponseStatusException.class, service::status).getStatusCode().value());
        service.update("SESSDATA=repaired-test");
        assertEquals("SESSDATA=repaired-test", service.currentCookie());
    }

    @Test
    void controllerAndExistingHttpClientUseReplacementsImmediatelyAndNeverReturnCookie() throws Exception {
        BilibiliAuthService service = auth("SESSDATA=environment-test");
        MockMvc mvc = mvc(service);
        List<Request> requests = new ArrayList<>();
        BilibiliHttpClient http = new BilibiliHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request());
            return BilibiliHttpClientTest.reply(chain.request(), 200, "application/json", "{\"code\":0,\"data\":{}}");
        }).build(), mapper, service::currentCookie);
        http.api("https://api.bilibili.com/x/test", "");
        assertEquals("SESSDATA=environment-test", requests.get(0).header("Cookie"));
        for (String cookie : List.of("SESSDATA=first-test", "SESSDATA=second-test", "")) {
            String body = mvc.perform(post("/api/bilibili/auth").header("X-Mujian-Settings", "1")
                            .contentType("application/json").content(mapper.writeValueAsString(Map.of("cookie", cookie))))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.configured").value(!cookie.isEmpty()))
                    .andExpect(jsonPath("$.cookie").doesNotExist()).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("SESSDATA"));
            mvc.perform(get("/api/bilibili/auth"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.configured").value(!cookie.isEmpty()))
                    .andExpect(jsonPath("$.cookie").doesNotExist());
            http.api("https://api.live.bilibili.com/x/test", "");
            assertEquals(cookie.isEmpty() ? null : cookie, requests.get(requests.size() - 1).header("Cookie"));
        }
        service.update("SESSDATA=media-must-not-receive");
        http.redirect("https://b23.tv/test");
        try (Response ignored = http.media(URI.create("https://cdn.bilivideo.com/media.mp4"), "", null, false)) {
            assertNull(requests.get(requests.size() - 1).header("Cookie"));
            assertNull(requests.get(requests.size() - 2).header("Cookie"));
        }
    }

    @Test
    void malformedJsonAndInvalidShapesNeverEchoSecretValues() throws Exception {
        MockMvc mvc = mvc(auth(""));
        for (String json : List.of("{\"cookie\":private_secret_token}", "{\"cookie\":\"private_secret_token\"",
                "{\"cookie\":null}", "{\"cookie\":12}", "{}", "{\"cookie\":\"test\",\"extra\":\"private_secret_token\"}")) {
            String result = mvc.perform(post("/api/bilibili/auth").header("X-Mujian-Settings", "1")
                            .contentType("application/json").content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(result.contains("private_secret_token"));
        }
        assertFalse(Files.exists(file()));
    }

    @Test
    void credentialWritesRequireTheDedicatedSameOriginHeaderAndRejectCrossOriginPreflight() throws Exception {
        MockMvc mvc = mvc(auth(""));
        mvc.perform(post("/api/bilibili/auth").contentType("application/json").content("{\"cookie\":\"secret\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/bilibili/auth").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,X-Mujian-Settings"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type"));
        mvc.perform(options("/api/bilibili/auth").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Mujian-Settings"))
                .andExpect(status().isForbidden());
        assertFalse(Files.exists(file()));
        mvc.perform(post("/api/bilibili/auth").header("Origin", "https://public.reverse-proxy.example")
                        .header("X-Mujian-Settings", "1").contentType("application/json").content("{\"cookie\":\"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentSavesLeaveMemoryAndDiskConsistent() throws Exception {
        BilibiliAuthService service = auth("");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> saves = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String value = "SESSDATA=test-" + i;
                saves.add(executor.submit(() -> service.update(value)));
            }
            for (Future<?> save : saves) {
                save.get();
            }
            assertEquals(service.currentCookie(), auth("").currentCookie());
            assertEquals(service.status(), auth("").status());
        } finally {
            executor.shutdownNow();
        }
    }

    private BilibiliAuthService auth(String environmentCookie) {
        return new BilibiliAuthService(mapper, data, environmentCookie);
    }

    private Path file() {
        return data.resolve("bilibili-auth.json");
    }

    private MockMvc mvc(BilibiliAuthService service) {
        TestCorsRegistry registry = new TestCorsRegistry();
        new CorsConfig().addCorsMappings(registry);
        UrlBasedCorsConfigurationSource cors = new UrlBasedCorsConfigurationSource();
        cors.setCorsConfigurations(registry.configurations());
        return MockMvcBuilders.standaloneSetup(new BilibiliAuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorsFilter(cors)).build();
    }

    private static class TestCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
