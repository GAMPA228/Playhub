package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptSearchAdmissionTest {

    private ScriptSpiderService scripts;
    private Semaphore searchSlots;
    private Semaphore allSlots;

    @BeforeEach
    void setUp() {
        AppPathsService paths = mock(AppPathsService.class);
        when(paths.getBaseDir()).thenReturn(Path.of("").toAbsolutePath());
        scripts = new ScriptSpiderService(paths, mock(ConfigService.class), new ObjectMapper());
        ReflectionTestUtils.setField(scripts, "scriptMaxConcurrency", 2);
        ReflectionTestUtils.setField(scripts, "scriptAdmissionTimeoutSeconds", 1L);
        scripts.afterPropertiesSet();
        searchSlots = (Semaphore) ReflectionTestUtils.getField(scripts, "scriptSearchSlots");
        allSlots = (Semaphore) ReflectionTestUtils.getField(scripts, "scriptSlots");
    }

    @Test
    void searchesCannotReserveAllScriptCapacity() throws Exception {
        assertEquals(1, searchSlots.availablePermits());
        searchSlots.acquire();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> run("search"));
        assertEquals("script search executor busy", failure.getMessage());
        assertEquals(2, allSlots.availablePermits());
    }

    @Test
    void playbackAdmissionDoesNotWaitForTheSearchReservation() throws Exception {
        searchSlots.acquire();
        // The deliberately nonexistent command proves playback reached process admission.
        assertThrows(IOException.class, () -> run("play"));
        assertEquals(2, allSlots.availablePermits());
        assertEquals(0, searchSlots.availablePermits());
    }

    @Test
    void failedProcessCreationReleasesBothPermits() {
        assertThrows(IOException.class, () -> run("search"));
        assertEquals(2, allSlots.availablePermits());
        assertEquals(1, searchSlots.availablePermits());
    }

    private void run(String action) throws Exception {
        Method method = ScriptSpiderService.class.getDeclaredMethod("runProcess", List.class, JsonNode.class);
        method.setAccessible(true);
        try {
            method.invoke(scripts, List.of("playhub-nonexistent-test-command-74b2"),
                    new ObjectMapper().createObjectNode().put("action", action));
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }
}
