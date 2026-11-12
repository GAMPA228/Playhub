package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private BilibiliHttpClient http;
    private BilibiliMediaService media;
    private BilibiliService service;

    @BeforeEach
    void setUp() {
        http = mock(BilibiliHttpClient.class);
        media = mock(BilibiliMediaService.class);
        service = new BilibiliService(http, media);
        when(media.createDash(anyString(), any(), any(), anyDouble()))
                .thenReturn(new BilibiliMediaService.Playback("/api/bilibili/play/test/manifest.mpd", "dash", 999999L));
        when(media.createHls(anyString(), anyString()))
                .thenReturn(new BilibiliMediaService.Playback("/api/bilibili/media/test/index.m3u8", "hls", 999999L));
        when(media.createFile(anyString(), anyString()))
                .thenReturn(new BilibiliMediaService.Playback("/api/bilibili/media/test/media.mp4", "mp4", 999999L));
    }

    @Test
    void identifiesVideoIdsShareTextPagesAndLiveRooms() {
        assertEquals(new BilibiliService.Link(false, "BV1xx411c7mD", 1), service.identify("BV1xx411c7mD"));
        assertEquals(new BilibiliService.Link(false, "av170001", 1), service.identify("AV170001"));
        assertEquals(new BilibiliService.Link(false, "BV1xx411c7mD", 2),
                service.identify("推荐 https://www.bilibili.com/video/BV1xx411c7mD/?p=2&share_source=copy。"));
        assertEquals(new BilibiliService.Link(true, "6", 1), service.identify("https://live.bilibili.com/6?broadcast_type=0"));
        assertEquals(new BilibiliService.Link(true, "7734200", 1), service.identify("7734200"));
        verifyNoInteractions(http);
    }

    @Test
    void rejectsLookalikeDomainsPrivateUrlsAndUnsupportedBilibiliPathsBeforeNetwork() {
        for (String input : List.of("http://127.0.0.1/a", "https://www.bilibili.com.evil.test/video/BV1xx411c7mD",
                "https://www.bilibili.com@evil.test/video/BV1xx411c7mD", "https://evil.test/?url=https://www.bilibili.com/video/BV1xx411c7mD",
                "https://www.bilibili.com:9999/video/BV1xx411c7mD", "https://www.bilibili.com/bangumi/play/ep123",
                "https://www.bilibili.com/cheese/play/ss123", "https://live.bilibili.com/not-a-room", "not-a-link",
                "https://www.bilibili.com/video/BV1xx411c7mD?p=0", "https://www.bilibili.com/video/BV1xx411c7mD?p=oops")) {
            assertThrows(IllegalArgumentException.class, () -> service.identify(input), input);
        }
        verifyNoInteractions(http);
    }

    @Test
    void expandsOfficialShortLinkAndRefusesCrossDomainRedirectWithoutRequestingIt() {
        when(http.redirect("https://b23.tv/safe")).thenReturn("https://www.bilibili.com/video/BV1xx411c7mD?p=2");
        assertEquals(2, service.identify("https://b23.tv/safe").page());
        when(http.redirect("https://b23.tv/evil")).thenReturn("http://127.0.0.1/admin");
        assertThrows(IllegalArgumentException.class, () -> service.identify("https://b23.tv/evil"));
        verify(http, times(2)).redirect(anyString());
        verify(http, never()).api(anyString(), anyString());
    }

    @Test
    void stopsShortLinkLoops() {
        when(http.redirect("https://b23.tv/loop")).thenReturn("https://b23.tv/loop");
        assertThrows(IllegalArgumentException.class, () -> service.identify("https://b23.tv/loop"));
        verify(http, times(5)).redirect(anyString());
    }

    @Test
    void videoSelectsAvcAndAudioAndPrefersRegularHttpsBackupCdn() throws Exception {
        mockVideoInfo();
        when(http.api(contains("/x/player/playurl"), anyString())).thenReturn(dash());
        BilibiliService.Result result = service.resolve("browser", "BV1xx411c7mD", null, 80);
        assertEquals("video", result.kind());
        assertEquals("dash", result.playback().format());
        assertEquals(32, result.quality());
        assertTrue(result.notice().contains("所选清晰度不可用"));
        assertEquals(List.of(32, 16), result.qualities().stream().map(BilibiliService.Quality::id).toList());
        ArgumentCaptor<JsonNode> video = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> audio = ArgumentCaptor.forClass(JsonNode.class);
        verify(media).createDash(eq("browser"), video.capture(), audio.capture(), eq(20.0));
        assertEquals("avc1.64001E", video.getValue().path("codecs").asText());
        assertEquals("https://cdn.bilivideo.com/video.m4s", video.getValue().path("baseUrl").asText());
        assertEquals("mp4a.40.2", audio.getValue().path("codecs").asText());
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", result.sourceUrl());
    }

    @Test
    void coverUsesHttpsWithoutAllowingUnrelatedHosts() throws Exception {
        mockVideoInfo();
        JsonNode info = http.api("https://api.bilibili.com/x/web-interface/view?aid=2", "");
        when(http.api(contains("/x/player/playurl"), anyString())).thenReturn(dash());
        for (String cover : List.of("http://i0.hdslb.com/cover.jpg", "//i0.hdslb.com/cover.jpg")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) info.path("data")).put("pic", cover);
            assertEquals("https://i0.hdslb.com/cover.jpg", service.resolve("browser", "av2", null, null).cover());
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) info.path("data")).put("pic", "https://evil.example/cover.jpg");
        assertEquals("", service.resolve("browser", "av2", null, null).cover());
    }

    @Test
    void invalidUnusedBackupDoesNotInvalidateAnAllowedCdn() throws Exception {
        mockVideoInfo();
        JsonNode data = dash();
        ((com.fasterxml.jackson.databind.node.ArrayNode) data.path("data").path("dash").path("video").get(1).path("backupUrl"))
                .add("http://127.0.0.1/private");
        when(http.api(contains("/x/player/playurl"), anyString())).thenReturn(data);
        assertEquals("dash", service.resolve("browser", "av2", null, null).playback().format());
        verify(media).createDash(eq("browser"), argThat(node -> node.path("baseUrl").asText()
                .equals("https://cdn.bilivideo.com/video.m4s")), any(), anyDouble());
    }

    @Test
    void selectedPageUsesItsOwnCidAndSelectedQuality() throws Exception {
        mockVideoInfo();
        when(http.api(contains("/x/player/playurl"), anyString())).thenReturn(dash());
        BilibiliService.Result result = service.resolve("browser", "https://www.bilibili.com/video/BV1xx411c7mD?p=2", null, 16);
        assertEquals(2, result.page());
        assertEquals(16, result.quality());
        verify(http).api(contains("cid=222"), anyString());
        assertEquals(2, result.pages().size());
        verify(media).createDash(eq("browser"), argThat(node -> node.path("id").asInt() == 16), any(), eq(20.0));
    }

    @Test
    void invalidPageDoesNotSilentlyPlayFirstPart() throws Exception {
        mockVideoInfo();
        assertThrows(IllegalArgumentException.class, () -> service.resolve("browser", "av2", 3, null));
        verify(http, never()).api(contains("/playurl"), anyString());
    }

    @Test
    void missingAudioOnlyFallsBackToSingleMuxedMp4() throws Exception {
        mockVideoInfo();
        when(http.api(contains("fnval=4048"), anyString())).thenReturn(json("{\"data\":{\"dash\":{\"video\":[]}}}"));
        when(http.api(contains("fnval=1&"), anyString())).thenReturn(json("""
                {"data":{"format":"mp4","quality":16,"durl":[{"url":"https://cdn.bilivideo.com/movie.mp4"}]}}
                """));
        assertEquals("mp4", service.resolve("browser", "av2", null, null).playback().format());
        verify(media, never()).createDash(anyString(), any(), any(), anyDouble());
    }

    @Test
    void refusesSilentTracksAndMultipleProgressiveSegments() throws Exception {
        mockVideoInfo();
        when(http.api(contains("fnval=4048"), anyString())).thenReturn(json("{\"data\":{\"dash\":{\"video\":[]}}}"));
        when(http.api(contains("fnval=1&"), anyString())).thenReturn(json("""
                {"data":{"format":"flv","durl":[{"url":"https://cdn.bilivideo.com/1.flv"},{"url":"https://cdn.bilivideo.com/2.flv"}]}}
                """));
        assertThrows(IllegalStateException.class, () -> service.resolve("browser", "av2", null, null));
        verifyNoInteractions(media);
    }

    @Test
    void offlineAndLoopRoomsHaveMetadataButNoPlayableUrl() throws Exception {
        for (int status : List.of(0, 2)) {
            mockRoom(status);
            BilibiliService.Result result = service.resolve("browser", "6", null, null);
            assertEquals(status == 0 ? "offline" : "loop", result.status());
            assertNull(result.playback());
            assertEquals("https://live.bilibili.com/7734200", result.sourceUrl());
        }
        verify(http, never()).api(contains("getRoomPlayInfo"), anyString());
        verifyNoInteractions(media);
    }

    @Test
    void liveUsesHlsAvcAndRealRoomIdWithAvailableQualities() throws Exception {
        mockRoom(1);
        when(http.api(contains("getRoomPlayInfo"), anyString())).thenReturn(json("""
                {"data":{"live_status":1,"playurl_info":{"playurl":{
                  "g_qn_desc":[{"qn":250,"desc":"超清"},{"qn":150,"desc":"高清"}],
                  "stream":[{"protocol_name":"http_hls","format":[{"format_name":"ts","codec":[{
                    "codec_name":"avc","current_qn":250,"accept_qn":[250,150],"base_url":"/live/index.m3u8?",
                    "url_info":[{"host":"https://cdn.bilivideo.com","extra":"expires=9999999999"}]
                  }]}]}]}}}}
                """));
        BilibiliService.Result result = service.resolve("browser", "https://live.bilibili.com/6", null, null);
        assertEquals("hls", result.playback().format());
        assertEquals(250, result.quality());
        assertEquals("超清", result.qualities().get(0).label());
        verify(media).createHls("browser", "https://cdn.bilivideo.com/live/index.m3u8?expires=9999999999");
    }

    @Test
    void roomThatStopsDuringResolutionReturnsOfflineInsteadOfAPlaybackFailure() throws Exception {
        mockRoom(1);
        when(http.api(contains("getRoomPlayInfo"), anyString())).thenReturn(json("{\"data\":{\"live_status\":0}}"));
        BilibiliService.Result result = service.resolve("browser", "6", null, null);
        assertEquals("offline", result.status());
        assertNull(result.playback());
        assertTrue(result.notice().contains("下播"));
        verifyNoInteractions(media);
    }

    @Test
    void rejectsLockedRoomsAndDoesNotFallBackToFlvOnlyStreams() throws Exception {
        when(http.api(contains("room_init"), anyString())).thenReturn(json("{\"data\":{\"room_id\":1,\"is_locked\":true}}"));
        assertThrows(IllegalStateException.class, () -> service.resolve("browser", "1", null, null));
        mockRoom(1);
        when(http.api(contains("getRoomPlayInfo"), anyString())).thenReturn(json("{\"data\":{\"playurl_info\":{\"playurl\":{\"stream\":[]}}}}"));
        assertThrows(IllegalStateException.class, () -> service.resolve("browser", "6", null, null));
    }

    @Test
    void releasesResolveSlotsOnFailureAndValidatesExplicitArguments() {
        when(http.api(anyString(), anyString())).thenThrow(new IllegalStateException("B站暂时不可用"));
        for (int i = 0; i < 5; i++) {
            assertEquals("B站暂时不可用", assertThrows(IllegalStateException.class,
                    () -> service.resolve("browser", "av2", null, null)).getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> service.resolve("browser", "av2", 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("browser", "av2", null, -1));
    }

    private void mockVideoInfo() throws Exception {
        when(http.api(contains("/x/web-interface/view"), anyString())).thenReturn(json("""
                {"data":{"bvid":"BV1xx411c7mD","title":"测试视频","pic":"https://i0.hdslb.com/cover.jpg",
                  "owner":{"name":"测试作者"},"pages":[{"page":1,"cid":111,"part":"第一集","duration":20},
                  {"page":2,"cid":222,"part":"第二集","duration":20}]}}
                """));
    }

    private void mockRoom(int status) throws Exception {
        when(http.api(contains("room_init"), anyString())).thenReturn(json("{\"data\":{\"room_id\":7734200,\"uid\":123,\"live_status\":" + status + "}}"));
        when(http.api(contains("get_info"), anyString())).thenReturn(json("{\"data\":{\"title\":\"测试直播\",\"user_cover\":\"https://i0.hdslb.com/cover.jpg\"}}"));
    }

    private JsonNode dash() throws Exception {
        return json("""
                {"data":{"timelength":20000,"dash":{"video":[
                  {"id":32,"codecs":"hev1.1","baseUrl":"https://cdn.bilivideo.com/hevc.m4s"},
                  {"id":32,"codecs":"avc1.64001E","baseUrl":"https://node.mcdn.bilivideo.cn:8082/video.m4s",
                    "backupUrl":["https://cdn.bilivideo.com/video.m4s"]},
                  {"id":16,"codecs":"avc1.64001E","baseUrl":"https://cdn.bilivideo.com/low.m4s"}],
                  "audio":[{"codecs":"mp4a.40.2","bandwidth":128000,"baseUrl":"https://cdn.bilivideo.com/audio.m4s"}]}}}
                """);
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }
}
