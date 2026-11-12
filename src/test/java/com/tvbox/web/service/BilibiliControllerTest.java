package com.tvbox.web.service;

import com.tvbox.web.controller.BilibiliController;
import com.tvbox.web.controller.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BilibiliControllerTest {

    private BilibiliService service;
    private BilibiliMediaService media;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BilibiliService.class);
        media = mock(BilibiliMediaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BilibiliController(service, media))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void requestValidationReturnsJsonBadRequestWithoutCallingResolver() throws Exception {
        for (String body : new String[]{"{}", "{\"input\":\"\"}", "{\"input\":\"av2\",\"page\":0}",
                "{\"input\":\"av2\",\"quality\":-1}", "{\"input\":\"" + "a".repeat(4097) + "\"}"}) {
            mvc.perform(post("/api/bilibili/resolve").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        }
        verifyNoInteractions(service);
    }

    @Test
    void manifestBelongsToCurrentHttpSessionAndIsNotCached() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(media.manifest(session.getId(), "grant")).thenReturn("<MPD/>");
        mvc.perform(get("/api/bilibili/play/grant/manifest.mpd").session(session))
                .andExpect(status().isOk()).andExpect(content().contentType("application/dash+xml"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string("<MPD/>"));
    }

    @Test
    void mediaStatusCodesSurviveGlobalExceptionMapping() throws Exception {
        when(media.manifest(anyString(), eq("expired"))).thenThrow(new ResponseStatusException(HttpStatus.GONE, "播放地址已过期，请重新解析"));
        mvc.perform(get("/api/bilibili/play/expired/manifest.mpd"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error").value("播放地址已过期，请重新解析"));
    }

    @Test
    void forwardsRangeAndHeadToTheRealSessionBoundProxy() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(head("/api/bilibili/media/grant/media.mp4").session(session)
                        .param("r", "resource").param("sig", "signature").header("Range", "bytes=0-99"))
                .andExpect(status().isOk());
        verify(media).stream(eq(session.getId()), eq("grant"), eq("resource"), eq("signature"), eq("bytes=0-99"), eq(true), any());
    }
}
