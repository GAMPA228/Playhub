package com.tvbox.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.catvod.crawler.Spider;
import com.tvbox.web.model.SiteDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpiderSearchIsolationTest {

    private static final String SESSION = "search-browser";
    private ConfigService config;
    private SpiderBridgeService bridge;
    private JarSpiderService jars;
    private SiteDefinition site;
    private final ExecutorService callers = Executors.newFixedThreadPool(3);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        config = mock(ConfigService.class);
        bridge = mock(SpiderBridgeService.class);
        jars = new JarSpiderService(config, new ObjectMapper(), bridge,
                mock(ScriptSpiderService.class), mock(AppPathsService.class));
        ReflectionTestUtils.setField(jars, "spiderOperationMaxConcurrency", 1);
        ReflectionTestUtils.setField(jars, "spiderOperationTimeoutSeconds", 2L);
        ReflectionTestUtils.setField(jars, "searchMaxConcurrency", 1);
        ReflectionTestUtils.setField(jars, "searchTimeoutSeconds", 1L);
        jars.afterPropertiesSet();
        site = new SiteDefinition();
        site.setUid("s0");
        site.setKey("test");
        site.setApi("csp_Test");
        site.setType(3);
        when(bridge.enabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        callers.shutdownNow();
        jars.destroy();
    }

    @Test
    void stuckSearchDoesNotConsumeThePlaybackLaneOrCreateReplacementThreads() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        cache(new Spider() {
            @Override
            public String searchContent(String key, boolean quick) {
                started.countDown();
                // Deliberately emulate a third-party plugin that ignores interruption.
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                }
                return "{\"list\":[]}";
            }

            @Override
            public String playerContent(String flag, String id, List<String> flags) {
                return "{\"url\":\"https://media.example/video.mp4\"}";
            }
        });
        Future<?> search = callers.submit(() -> assertThrows(IllegalStateException.class,
                () -> jars.search(SESSION, site, "test", false)));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals("https://media.example/video.mp4",
                callers.submit(() -> jars.play(SESSION, site, "line", "id", List.of()))
                        .get(1, TimeUnit.SECONDS).path("url").asText());
        search.get(3, TimeUnit.SECONDS);
        assertEquals(1, searchPool().getActiveCount());
        assertEquals(1, searchPool().getMaximumPoolSize());
        verify(bridge, never()).search(any(), anyString(), anyBoolean());
    }

    @Test
    void initializationIsInsideTheSearchDeadlineAndIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(config.getConfigUrl(SESSION)).thenAnswer(invocation -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("initialization interrupted", ex);
            }
            return "";
        });
        Future<?> result = callers.submit(() -> {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> jars.search(SESSION, site, "test", false));
            assertTrue(error.getMessage().contains("超时"));
        });
        result.get(3, TimeUnit.SECONDS);
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        verify(bridge, never()).search(any(), anyString(), anyBoolean());
    }

    @Test
    void cancellingAQueuedSearchRemovesItAndDoesNotInvokeThePlugin() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        searchPool().execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        Future<?> queued = callers.submit(() -> jars.search(SESSION, site, "test", false));
        await(() -> searchPool().getQueue().size() == 1);
        queued.cancel(true);
        await(() -> searchPool().getQueue().isEmpty());
        verify(config, never()).getConfigUrl(anyString());
        verify(bridge, never()).search(any(), anyString(), anyBoolean());
    }

    @Test
    void rejectedSearchDoesNotEscapeThroughBridgeFallback() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        searchPool().execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        searchPool().execute(() -> {});
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jars.search(SESSION, site, "test", false));
        assertTrue(error.getMessage().contains("search executor busy"));
        verify(bridge, never()).search(any(), anyString(), anyBoolean());
    }

    private ThreadPoolExecutor searchPool() {
        return (ThreadPoolExecutor) ReflectionTestUtils.getField(jars, "spiderSearchThreadPool");
    }

    @SuppressWarnings("unchecked")
    private void cache(Spider spider) {
        String key = ReflectionTestUtils.invokeMethod(jars, "buildSpiderCacheKey", SESSION, site, false);
        BoundedCache<String, Object> cache = (BoundedCache<String, Object>) ReflectionTestUtils.getField(jars, "spiderMap");
        cache.put(key, spider);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
