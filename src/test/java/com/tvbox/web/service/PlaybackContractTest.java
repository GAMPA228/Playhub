package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.catvod.crawler.Spider;
import com.tvbox.web.model.ConfigPayload;
import com.tvbox.web.model.SiteDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackContractTest {

    private static final String SESSION = "browser-a";
    private static final String URL = "https://media.example/video.m3u8";
    private static final String FLAG = "线路一";
    private static final List<String> VIP_FLAGS = List.of("vip");
    private final ObjectMapper mapper = new ObjectMapper();
    private ConfigService config;
    private SpiderBridgeService bridge;
    private ScriptSpiderService scripts;
    private JarSpiderService jars;
    private SiteDefinition site;

    @BeforeEach
    void setUp() {
        config = mock(ConfigService.class);
        bridge = mock(SpiderBridgeService.class);
        scripts = mock(ScriptSpiderService.class);
        jars = new JarSpiderService(config, mapper, bridge, scripts, mock(AppPathsService.class));
        ReflectionTestUtils.setField(jars, "spiderOperationTimeoutSeconds", 2L);
        jars.afterPropertiesSet();
        site = site("csp_Test");
    }

    @AfterEach
    void tearDown() {
        jars.destroy();
    }

    static Stream<String> playbackIds() {
        return Stream.of(
                "|" + URL + "||复仇者联盟4：终局之战|1",
                URL + "|影片名称|第一集",
                "16203|影片名称|第一集",
                "token@" + URL,
                "token@" + URL + "@线路1",
                "token," + URL,
                "token~" + URL,
                "share+file+token$with%2Bsign",
                "{\"url\":\"" + URL + "\",\"token\":\"x|y\"}",
                "parse_api=https://parse.example/?url=" + URL + "&token=a%2Bb|片名|1",
                URL);
    }

    @ParameterizedTest
    @MethodSource("playbackIds")
    void facadePassesFullSpiderId(String id) {
        JarSpiderService mockJars = mock(JarSpiderService.class);
        TvboxFacadeService facade = facade(mockJars, site);
        JsonNode result = playResult(URL);
        when(mockJars.play(SESSION, site, FLAG, id, VIP_FLAGS)).thenReturn(result);

        assertSame(result, facade.play(SESSION, "s4", FLAG, id));
        verify(mockJars).play(SESSION, site, FLAG, id, VIP_FLAGS);
    }

    @ParameterizedTest
    @MethodSource("playbackIds")
    void localJarAndGatewayPreserveFullId(String id) {
        AtomicReference<String> received = new AtomicReference<>();
        Spider spider = new Spider() {
            @Override
            public String playerContent(String flag, String input, List<String> vipFlags) {
                assertEquals(FLAG, flag);
                assertEquals(VIP_FLAGS, vipFlags);
                received.set(input);
                return playResult("标签," + URL).toString();
            }
        };
        cacheSpider(SESSION, spider);
        cacheSpider(null, spider);

        JsonNode result = jars.play(SESSION, site, FLAG, id, VIP_FLAGS);
        assertEquals(id, received.get());
        assertEquals(id, result.path("key").asText());
        assertEquals(URL, result.path("url").asText());

        result = jars.playLocal(site, FLAG, id, VIP_FLAGS);
        assertEquals(id, received.get());
        assertEquals(id, result.path("key").asText());
        assertEquals(URL, result.path("url").asText());
    }

    @ParameterizedTest
    @MethodSource("playbackIds")
    void scriptDispatchPreservesFullId(String id) {
        when(scripts.supports(site)).thenReturn(true);
        JsonNode result = playResult(URL);
        when(scripts.play(site, FLAG, id, VIP_FLAGS)).thenReturn(result);

        assertSame(result, jars.play(SESSION, site, FLAG, id, VIP_FLAGS));
        assertSame(result, jars.playLocal(site, FLAG, id, VIP_FLAGS));
        verify(scripts, times(2)).play(site, FLAG, id, VIP_FLAGS);
    }

    @Test
    void bridgeFallbackPreservesFullId() {
        String id = "token|" + URL + "||片名|1";
        cacheSpider(SESSION, new Spider() {
            @Override
            public String playerContent(String flag, String input, List<String> vipFlags) {
                throw new IllegalStateException("local parser failed");
            }
        });
        when(bridge.enabled()).thenReturn(true);
        when(bridge.play(site, FLAG, id, VIP_FLAGS)).thenReturn(playResult(URL));

        assertEquals(URL, jars.play(SESSION, site, FLAG, id, VIP_FLAGS).path("url").asText());
        verify(bridge).play(site, FLAG, id, VIP_FLAGS);
    }

    @Test
    void validLocalPlayDoesNotTriggerUnnecessaryBridgeFallback() {
        cacheSpider(SESSION, new Spider() {
            @Override
            public String playerContent(String flag, String id, List<String> vipFlags) {
                return playResult(URL).toString();
            }
        });
        when(bridge.enabled()).thenReturn(true);

        assertEquals(URL, jars.play(SESSION, site, FLAG, "16203", VIP_FLAGS).path("url").asText());
        verify(bridge, never()).play(any(), anyString(), anyString(), anyList());
    }

    @Test
    void parserErrorIsNotConvertedIntoASuccessfulPlay() {
        cacheSpider(SESSION, new Spider() {
            @Override
            public String playerContent(String flag, String id, List<String> vipFlags) {
                throw new ArrayIndexOutOfBoundsException("Index 1 out of bounds for length 1");
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> jars.play(SESSION, site, FLAG, "opaque-id", VIP_FLAGS));
        assertEquals("Spider 调用失败: Index 1 out of bounds for length 1", failure.getMessage());
    }

    @Test
    void httpSourceRetainsExistingDirectUrlHandling() {
        HttpSourceService http = mock(HttpSourceService.class);
        site.setType(1);
        when(config.getSite(SESSION, "s4")).thenReturn(Optional.of(site));
        when(http.request(site, Map.of("play", URL, "flag", FLAG))).thenReturn(playResult("标签," + URL));
        TvboxFacadeService facade = new TvboxFacadeService(config, http, jars);

        assertEquals(URL, facade.play(SESSION, "s4", FLAG, "标签," + URL).path("url").asText());
        verify(http).request(site, Map.of("play", URL, "flag", FLAG));
    }

    @Test
    void playbackCacheDoesNotLeakBetweenBrowserSessions() {
        JarSpiderService mockJars = mock(JarSpiderService.class);
        TvboxFacadeService facade = facade(mockJars, site);
        when(config.getSite("browser-b", "s4")).thenReturn(Optional.of(site));
        when(config.getPayload("browser-b")).thenReturn(configPayload());
        when(mockJars.play(SESSION, site, FLAG, "1", VIP_FLAGS)).thenReturn(playResult(URL));
        when(mockJars.play("browser-b", site, FLAG, "1", VIP_FLAGS)).thenReturn(playResult("https://other.example/video.mp4"));

        JsonNode first = facade.play(SESSION, "s4", FLAG, "1");
        JsonNode second = facade.play("browser-b", "s4", FLAG, "1");
        assertNotEquals(first.path("url"), second.path("url"));
        assertSame(first, facade.play(SESSION, "s4", FLAG, "1"));
        verify(mockJars, times(1)).play(SESSION, site, FLAG, "1", VIP_FLAGS);
    }

    @Test
    void emptyPlayResponseIsNotCachedAndCanBeRetried() {
        JarSpiderService mockJars = mock(JarSpiderService.class);
        TvboxFacadeService facade = facade(mockJars, site);
        JsonNode empty = mapper.createObjectNode();
        JsonNode playable = playResult(URL);
        when(mockJars.play(SESSION, site, FLAG, "1", VIP_FLAGS)).thenReturn(empty).thenReturn(playable);

        assertSame(empty, facade.play(SESSION, "s4", FLAG, "1"));
        assertSame(playable, facade.play(SESSION, "s4", FLAG, "1"));
        assertSame(playable, facade.play(SESSION, "s4", FLAG, "1"));
        verify(mockJars, times(2)).play(SESSION, site, FLAG, "1", VIP_FLAGS);
    }

    @Test
    void reloadingConfigWithTheSameUidCannotReuseOldPlayback() {
        JarSpiderService mockJars = mock(JarSpiderService.class);
        TvboxFacadeService facade = facade(mockJars, site);
        SiteDefinition replacement = site("csp_Test");
        when(config.getSite(SESSION, "s4")).thenReturn(Optional.of(site)).thenReturn(Optional.of(replacement));
        when(mockJars.play(SESSION, site, FLAG, "1", VIP_FLAGS)).thenReturn(playResult(URL));
        when(mockJars.play(SESSION, replacement, FLAG, "1", VIP_FLAGS)).thenReturn(playResult("https://new.example/video.mp4"));

        JsonNode first = facade.play(SESSION, "s4", FLAG, "1");
        JsonNode second = facade.play(SESSION, "s4", FLAG, "1");
        assertNotEquals(first.path("url"), second.path("url"));
        verify(mockJars).play(SESSION, replacement, FLAG, "1", VIP_FLAGS);
    }

    private TvboxFacadeService facade(JarSpiderService mockJars, SiteDefinition selected) {
        when(config.getSite(SESSION, "s4")).thenReturn(Optional.of(selected));
        when(config.getPayload(SESSION)).thenReturn(configPayload());
        return new TvboxFacadeService(config, mock(HttpSourceService.class), mockJars);
    }

    private ConfigPayload configPayload() {
        ConfigPayload payload = new ConfigPayload();
        payload.setFlags(VIP_FLAGS);
        return payload;
    }

    private SiteDefinition site(String api) {
        SiteDefinition selected = new SiteDefinition();
        selected.setUid("s4");
        selected.setKey("test");
        selected.setType(3);
        selected.setApi(api);
        return selected;
    }

    private JsonNode playResult(String url) {
        return mapper.createObjectNode().put("url", url).put("parse", 0);
    }

    @SuppressWarnings("unchecked")
    private void cacheSpider(String sessionId, Spider spider) {
        String key = ReflectionTestUtils.invokeMethod(jars, "buildSpiderCacheKey", sessionId, site, false);
        BoundedCache<String, Object> cache = (BoundedCache<String, Object>) ReflectionTestUtils.getField(jars, "spiderMap");
        cache.put(key, spider);
    }
}
