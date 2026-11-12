package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliMediaServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dashManifestContainsSeparatePlayableAudioAndVideoAndRangeProxy() throws Exception {
        List<Request> requests = new ArrayList<>();
        BilibiliMediaService media = service(requests, request -> BilibiliHttpClientTest.reply(request, 206, "video/mp4", "0123456789")
                .newBuilder().header("Content-Range", "bytes 0-9/1000").header("Accept-Ranges", "bytes").header("Content-Length", "10").build());
        BilibiliMediaService.Playback playback = media.createDash("browser", track("video", "https://cdn.bilivideo.com/v.m4s", "avc1.64001F"),
                track("audio", "https://cdn.bilivideo.com/a.m4s", "mp4a.40.2"), 120.5);
        String xml = media.manifest("browser", id(playback.url()));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, document.getElementsByTagName("AdaptationSet").getLength());
        assertTrue(xml.contains("audio/mp4"));
        assertTrue(xml.contains("video/mp4"));
        assertTrue(xml.contains("<Initialization range=\"0-999\""));
        assertTrue(xml.contains("indexRange=\"1000-1500\""));
        assertTrue(xml.contains("PT120.500S"));
        String proxy = document.getElementsByTagName("BaseURL").item(0).getTextContent();
        MockHttpServletResponse response = fetch(media, "browser", proxy, "bytes=0-9", false);
        assertEquals(206, response.getStatus());
        assertEquals("bytes 0-9/1000", response.getHeader("Content-Range"));
        assertEquals("10", response.getHeader("Content-Length"));
        assertEquals("0123456789", response.getContentAsString());
        assertEquals("bytes=0-9", requests.get(0).header("Range"));
        assertNull(requests.get(0).header("Cookie"));
    }

    @Test
    void hlsRewritesRelativeNestedManifestsMapAndKeysAgainstFinalRedirectUrl() throws Exception {
        BilibiliMediaService media = service(new ArrayList<>(), request -> {
            if (request.url().encodedPath().equals("/root.m3u8")) {
                return BilibiliHttpClientTest.reply(request, 302, "text/plain", "").newBuilder()
                        .header("Location", "https://cdn.bilivideo.com/real/master.m3u8").build();
            }
            if (request.url().encodedPath().equals("/real/master.m3u8")) {
                return BilibiliHttpClientTest.reply(request, 200, "application/vnd.apple.mpegurl", """
                        #EXTM3U
                        #EXT-X-KEY:METHOD=AES-128,URI="key.bin?x=1"
                        #EXT-X-MAP:URI="init.mp4"
                        #EXT-X-STREAM-INF:BANDWIDTH=100
                        variant/live.m3u8?token=x
                        """);
            }
            if (request.url().encodedPath().equals("/real/variant/live.m3u8")) {
                return BilibiliHttpClientTest.reply(request, 200, "text/plain", "#EXTM3U\n#EXTINF:2,\n../segment.ts\n");
            }
            return BilibiliHttpClientTest.reply(request, 200, "video/mp2t", "segment");
        });
        var playback = media.createHls("browser", "https://cdn.bilivideo.com/root.m3u8");
        assertTrue(playback.url().contains("index.m3u8"));
        String playlist = fetch(media, "browser", playback.url(), null, false).getContentAsString();
        assertFalse(playlist.contains("https://cdn.bilivideo.com"));
        Matcher attributes = Pattern.compile("URI=\"([^\"]+)\"").matcher(playlist);
        assertTrue(attributes.find());
        assertEquals("https://cdn.bilivideo.com/real/key.bin?x=1", decodedResource(attributes.group(1)));
        assertTrue(attributes.find());
        assertEquals("https://cdn.bilivideo.com/real/init.mp4", decodedResource(attributes.group(1)));
        String nestedUrl = playlist.lines().filter(line -> line.startsWith("/api/")).findFirst().orElseThrow();
        String nested = fetch(media, "browser", nestedUrl, null, false).getContentAsString();
        String segment = nested.lines().filter(line -> line.startsWith("/api/")).findFirst().orElseThrow();
        assertEquals("https://cdn.bilivideo.com/real/segment.ts", decodedResource(segment));
        assertEquals("segment", fetch(media, "browser", segment, null, false).getContentAsString());
    }

    @Test
    void contextsAreSessionBoundAndExpiredOrEvictedContextsCannotBeUsed() throws Exception {
        MutableClock clock = new MutableClock();
        BilibiliMediaService media = service(clock, new ArrayList<>(), request -> BilibiliHttpClientTest.reply(request, 200, "video/mp4", "ok"));
        var first = media.createFile("one", "https://cdn.bilivideo.com/a.mp4");
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> fetch(media, "two", first.url(), null, false)).getStatusCode().value());
        clock.now = clock.now.plusSeconds(3601);
        assertEquals(410, assertThrows(ResponseStatusException.class, () -> fetch(media, "one", first.url(), null, false)).getStatusCode().value());
        var oldest = media.createFile("one", "https://cdn.bilivideo.com/a.mp4");
        for (int i = 0; i < 64; i++) {
            media.createFile("one", "https://cdn.bilivideo.com/" + i + ".mp4");
        }
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> fetch(media, "one", oldest.url(), null, false)).getStatusCode().value());
    }

    @Test
    void signaturesAndRangesAreCheckedBeforeAnyMediaRequest() {
        List<Request> requests = new ArrayList<>();
        BilibiliMediaService media = service(requests, request -> BilibiliHttpClientTest.reply(request, 200, "video/mp4", "ok"));
        var playback = media.createFile("browser", "https://cdn.bilivideo.com/a.mp4");
        String forged = playback.url().replace("sig=", "sig=forged");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> fetch(media, "browser", forged, null, false)).getStatusCode().value());
        String forgedResource = playback.url().replace(query(playback.url(), "r"), Base64.getUrlEncoder().withoutPadding()
                .encodeToString("http://127.0.0.1/admin".getBytes(StandardCharsets.UTF_8)));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> fetch(media, "browser", forgedResource, null, false)).getStatusCode().value());
        for (String range : List.of("bytes=", "bytes=-", "bytes=10-1", "bytes=0-10,20-30", "bytes=-0", "bytes=9999999999999999999-")) {
            assertEquals(416, assertThrows(ResponseStatusException.class, () -> fetch(media, "browser", playback.url(), range, false)).getStatusCode().value());
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void maliciousHlsReferencesAndNonMediaRedirectsAreRejected() {
        for (String reference : List.of("http://192.168.1.1/admin", "https://cdn.bilivideo.com.evil.example/a", "file:///etc/passwd")) {
            BilibiliMediaService media = service(new ArrayList<>(), request -> BilibiliHttpClientTest.reply(request, 200,
                    "application/vnd.apple.mpegurl", "#EXTM3U\n" + reference + "\n"));
            var playback = media.createHls("browser", "https://cdn.bilivideo.com/root.m3u8");
            assertEquals(502, assertThrows(ResponseStatusException.class, () -> fetch(media, "browser", playback.url(), null, false)).getStatusCode().value());
        }
        BilibiliMediaService redirected = service(new ArrayList<>(), request -> BilibiliHttpClientTest.reply(request, 302, "text/plain", "")
                .newBuilder().header("Location", "http://127.0.0.1/secret").build());
        var playback = redirected.createFile("browser", "https://cdn.bilivideo.com/a.mp4");
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> fetch(redirected, "browser", playback.url(), null, false)).getStatusCode().value());
    }

    @Test
    void partialPlaylistsNeverBypassRewriting() {
        List<Request> requests = new ArrayList<>();
        BilibiliMediaService media = service(requests, request -> BilibiliHttpClientTest.reply(request, 206,
                "application/vnd.apple.mpegurl", "#EXTM3U\nhttps://cdn.bilivideo.com/raw.ts\n"));
        var playlist = media.createHls("browser", "https://cdn.bilivideo.com/root.m3u8");
        assertEquals(416, assertThrows(ResponseStatusException.class, () -> fetch(media, "browser", playlist.url(), "bytes=0-", false)).getStatusCode().value());
        assertTrue(requests.isEmpty());
        var hiddenPlaylist = media.createHls("browser", "https://cdn.bilivideo.com/playlist");
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> fetch(media, "browser", hiddenPlaylist.url(), "bytes=0-", false)).getStatusCode().value());
    }

    @Test
    void browserDisconnectDoesNotAttemptToAppendAnErrorAndReleasesTheStreamPermit() throws Exception {
        BilibiliMediaService media = service(new ArrayList<>(), request -> BilibiliHttpClientTest.reply(request, 200, "video/mp4", "data"));
        var playback = media.createFile("browser", "https://cdn.bilivideo.com/a.mp4");
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse closed = new MockHttpServletResponse() {
                @Override
                public ServletOutputStream getOutputStream() {
                    return new ServletOutputStream() {
                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener listener) {
                        }

                        @Override
                        public void write(int value) throws IOException {
                            throw new AsyncRequestNotUsableException("Browser closed connection");
                        }
                    };
                }
            };
            media.stream("browser", id(playback.url()), query(playback.url(), "r"), query(playback.url(), "sig"), null, false, closed);
            assertEquals(0, closed.getContentAsByteArray().length);
        }
        assertEquals("data", fetch(media, "browser", playback.url(), null, false).getContentAsString());
    }

    @Test
    void headAndUnsatisfiedRangeResponsesKeepStatusAndHeadersWithoutLeakingCookies() throws Exception {
        List<Request> requests = new ArrayList<>();
        BilibiliMediaService media = service(requests, request -> BilibiliHttpClientTest.reply(request, 416, "text/plain", "")
                .newBuilder().header("Content-Range", "bytes */123").header("Set-Cookie", "secret=value").build());
        var playback = media.createFile("browser", "https://cdn.bilivideo.com/a.mp4");
        var response = fetch(media, "browser", playback.url(), "bytes=999-", true);
        assertEquals(416, response.getStatus());
        assertEquals("bytes */123", response.getHeader("Content-Range"));
        assertNull(response.getHeader("Set-Cookie"));
        assertEquals(0, response.getContentAsByteArray().length);
        assertEquals("HEAD", requests.get(0).method());
    }

    @Test
    void playbackExpiryHonorsTheUpstreamDeadlineAndMissingAudioFailsClearly() throws Exception {
        MutableClock clock = new MutableClock();
        BilibiliMediaService media = service(clock, new ArrayList<>(), request -> BilibiliHttpClientTest.reply(request, 200, "video/mp4", "ok"));
        long deadline = clock.instant().getEpochSecond() + 120;
        var playback = media.createFile("browser", "https://cdn.bilivideo.com/a.mp4?deadline=" + deadline);
        assertEquals(deadline * 1000 - 15000, playback.expiresAt());
        assertThrows(IllegalStateException.class, () -> media.createDash("browser", track("video", "https://cdn.bilivideo.com/v.m4s", "avc1.64001F"), null, 120));
        assertThrows(IllegalStateException.class, () -> media.createFile("browser", "https://cdn.bilivideo.com/a.mp4?expires=" + (deadline - 200)));
    }

    private JsonNode track(String kind, String url, String codecs) throws Exception {
        return mapper.readTree("""
                {"id":32,"baseUrl":"%s","codecs":"%s","bandwidth":100000,"width":640,"height":360,
                "SegmentBase":{"Initialization":"0-999","indexRange":"1000-1500"}}
                """.formatted(url, codecs));
    }

    private BilibiliMediaService service(List<Request> requests, Handler handler) {
        return service(Clock.systemUTC(), requests, handler);
    }

    private BilibiliMediaService service(Clock clock, List<Request> requests, Handler handler) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request());
            return handler.respond(chain.request());
        }).build();
        return new BilibiliMediaService(new BilibiliHttpClient(client, mapper, "private-cookie=value"), clock);
    }

    private MockHttpServletResponse fetch(BilibiliMediaService media, String session, String url, String range, boolean head) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        media.stream(session, id(url), query(url, "r"), query(url, "sig"), range, head, response);
        return response;
    }

    private static String id(String url) {
        return URI.create(url).getPath().split("/")[4];
    }

    private static String query(String url, String name) {
        String raw = URI.create(url).getRawQuery();
        if (raw != null) {
            for (String item : raw.split("&")) {
                String[] pair = item.split("=", 2);
                if (pair[0].equals(name)) {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static String decodedResource(String url) {
        return new String(Base64.getUrlDecoder().decode(query(url, "r")), StandardCharsets.UTF_8);
    }

    private interface Handler {

        Response respond(Request request);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-12T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
