package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tvbox.web.model.ConfigPayload;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchSchedulingTest {

    private final ConfigService config = mock(ConfigService.class);
    private final HttpSourceService http = mock(HttpSourceService.class);
    private TvboxFacadeService facade;
    private List<SiteDefinition> sources;

    @BeforeEach
    void setUp() {
        facade = new TvboxFacadeService(config, http, mock(JarSpiderService.class));
        facade.afterPropertiesSet();
        sources = IntStream.range(0, 77).mapToObj(index -> {
            SiteDefinition site = new SiteDefinition();
            site.setUid("s" + index);
            site.setKey("source-" + index);
            site.setName("源" + index);
            site.setType(1);
            site.setApi("https://source.example/" + index);
            return site;
        }).toList();
        ConfigPayload payload = new ConfigPayload();
        payload.setSites(sources);
        when(config.getPayload("browser-a")).thenReturn(payload);
    }

    @AfterEach
    void tearDown() {
        facade.destroy();
    }

    @Test
    void seventySevenSourcesUseAtMostTwoGlobalSourceWorkers() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(http.request(any(), any())).thenAnswer(invocation -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                Thread.sleep(3);
                return result();
            } finally {
                active.decrementAndGet();
            }
        });
        ProgressTrackerService.ProgressInfo progress = new ProgressTrackerService.ProgressInfo(77, "browser-a");

        facade.searchAllBackground("browser-a", "测试", false, progress);
        await(progress::isDone);

        assertEquals(77, progress.getCompleted());
        assertEquals(77, progress.getResults().size());
        assertEquals(0, progress.getFailed());
        assertTrue(maximum.get() <= 2);
        assertEquals(2, ((ThreadPoolExecutor) ReflectionTestUtils.getField(facade, "searchSourceExecutor"))
                .getMaximumPoolSize());
    }

    @Test
    void cancellingInterruptsActiveSourcesAndDoesNotDispatchRemainingSeventyFive() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(http.request(any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return result();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw ex;
            }
        });
        ProgressTrackerService.ProgressInfo progress = new ProgressTrackerService.ProgressInfo(77, "browser-a");
        facade.searchAllBackground("browser-a", "测试", false, progress);
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        progress.cancel();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        Thread.sleep(75);
        assertEquals(2, calls.get());
        assertTrue(progress.isDone());
        assertTrue(progress.isCancelled());
        assertEquals(0, progress.getFailed());
        assertEquals(0, progress.getCompleted());
    }

    @Test
    void singleSourceTimeoutInterruptsTheEntireHttpSearch() throws Exception {
        ReflectionTestUtils.setField(facade, "searchTimeoutSeconds", 1L);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(http.request(any(), any())).thenAnswer(invocation -> {
            try {
                new CountDownLatch(1).await();
                return result();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw ex;
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> facade.search("browser-a", "s0", "测试", false));

        assertTrue(failure.getMessage().contains("搜索超时(1s)"));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void cancelledWaitingRequestsAreRemovedFromTheBoundedSourceQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(http.request(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return result();
        });
        ExecutorService callers = Executors.newFixedThreadPool(4);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(facade, "searchSourceExecutor");
        try {
            Future<?> first = callers.submit(() -> facade.search("browser-a", "s0", "测试", false));
            Future<?> second = callers.submit(() -> facade.search("browser-a", "s1", "测试", false));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> waitingA = callers.submit(() -> facade.search("browser-a", "s2", "测试", false));
            Future<?> waitingB = callers.submit(() -> facade.search("browser-a", "s3", "测试", false));
            await(() -> pool.getQueue().size() == 2);

            waitingA.cancel(true);
            waitingB.cancel(true);
            await(() -> pool.getQueue().isEmpty());
            release.countDown();

            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void abandonedPollingStopsBeforeAnotherSourceIsDispatched() throws Exception {
        ProgressTrackerService.ProgressInfo progress = new ProgressTrackerService.ProgressInfo(77, "browser-a");
        ReflectionTestUtils.setField(progress, "lastAccess", System.currentTimeMillis() - 46_000L);

        facade.searchAllBackground("browser-a", "测试", false, progress);
        await(progress::isDone);

        assertTrue(progress.isCancelled());
        assertEquals(0, progress.getCompleted());
        verify(http, never()).request(any(), any());
    }

    @Test
    void emptySourcesAndEverySourceFailingBothProduceTerminalProgress() throws Exception {
        ProgressTrackerService.ProgressInfo empty = new ProgressTrackerService.ProgressInfo(0, "browser-a");
        facade.searchAllBackground("browser-a", "测试", false, List.of(), empty);
        assertTrue(empty.isDone());
        assertTrue(empty.snapshot().path("results").isEmpty());

        when(http.request(any(), any())).thenThrow(new IllegalStateException("上游失效"));
        ProgressTrackerService.ProgressInfo failed = new ProgressTrackerService.ProgressInfo(2, "browser-a");
        facade.searchAllBackground("browser-a", "测试", false, sources.subList(0, 2), failed);
        await(failed::isDone);

        assertEquals(2, failed.getCompleted());
        assertEquals(2, failed.getFailed());
        assertTrue(failed.getResults().isEmpty());
        assertFalse(failed.isCancelled());
    }

    @Test
    void currentSourceScopeDoesNotSearchOtherSources() throws Exception {
        when(http.request(any(), any())).thenReturn(result());
        List<SiteDefinition> selected = facade.searchableSources("browser-a", "s17");
        ProgressTrackerService.ProgressInfo progress = new ProgressTrackerService.ProgressInfo(1, "browser-a");

        facade.searchAllBackground("browser-a", "测试", false, selected, progress);
        await(progress::isDone);

        assertEquals(List.of(sources.get(17)), selected);
        assertEquals(1, progress.getResults().size());
        assertEquals("s17", progress.getResults().get(0).path("source_uid").asText());
    }

    @Test
    void explicitSourceErrorsCountAsFailuresButNormalEmptyListsDoNot() throws Exception {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        when(http.request(any(), any())).thenAnswer(invocation -> {
            SiteDefinition site = invocation.getArgument(0);
            if (site == sources.get(0)) {
                return factory.objectNode().put("ok", false).put("error", "鉴权失败");
            }
            if (site == sources.get(1)) {
                return factory.objectNode().put("error", "response is not valid JSON");
            }
            return factory.objectNode().set("list", factory.arrayNode());
        });
        ProgressTrackerService.ProgressInfo progress = new ProgressTrackerService.ProgressInfo(3, "browser-a");

        facade.searchAllBackground("browser-a", "测试", false, sources.subList(0, 3), progress);
        await(progress::isDone);

        assertEquals(3, progress.getCompleted());
        assertEquals(2, progress.getFailed());
        assertTrue(progress.getResults().isEmpty());
    }

    private static JsonNode result() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        return factory.objectNode().set("list", factory.arrayNode()
                .add(factory.objectNode().put("vod_id", "1").put("vod_name", "测试")));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "异步任务没有及时到达预期状态");
    }
}
