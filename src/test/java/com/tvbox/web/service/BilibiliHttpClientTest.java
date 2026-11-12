package com.tvbox.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliHttpClientTest {

    @Test
    void accountCookieIsOnlySentToFixedApiHosts() throws Exception {
        List<Request> requests = new ArrayList<>();
        BilibiliHttpClient http = client(requests, request -> reply(request, 200, "application/json", "{\"code\":0,\"data\":{\"title\":\"test\"}}"));
        assertEquals("test", http.api("https://api.bilibili.com/x/test", "https://www.bilibili.com/").path("data").path("title").asText());
        try (Response ignored = http.media(URI.create("https://cdn.bilivideo.com/video.mp4"), "https://www.bilibili.com/", "bytes=0-9", false)) {
            assertEquals("private-session=value", requests.get(0).header("Cookie"));
            assertNull(requests.get(1).header("Cookie"));
            assertEquals("bytes=0-9", requests.get(1).header("Range"));
        }
        assertThrows(IllegalArgumentException.class, () -> http.api("https://evil.example/x", ""));
        assertThrows(IllegalArgumentException.class, () -> http.api("http://api.bilibili.com/x", ""));
        assertEquals(2, requests.size());
    }

    @Test
    void mediaRedirectsAreValidatedBeforeEachFetchAndNeverReceiveCookie() throws Exception {
        List<Request> requests = new ArrayList<>();
        BilibiliHttpClient http = client(requests, request -> {
            if (request.url().encodedPath().equals("/first")) {
                return reply(request, 302, "text/plain", "").newBuilder().header("Location", "https://other.bilivideo.cn:8082/final").build();
            }
            return reply(request, 200, "video/mp4", "test");
        });
        try (Response response = http.media(URI.create("https://cdn.bilivideo.com/first"), "https://live.bilibili.com/", null, false)) {
            assertEquals("other.bilivideo.cn", response.request().url().host());
        }
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.header("Cookie") == null));
        BilibiliHttpClient unsafe = client(new ArrayList<>(), request -> reply(request, 302, "text/plain", "")
                .newBuilder().header("Location", "http://127.0.0.1/admin").build());
        assertThrows(IllegalArgumentException.class, () -> unsafe.media(URI.create("https://cdn.bilivideo.com/first"), "", null, false));
    }

    @Test
    void shortLinksCannotRedirectOffPlatformAndDoNotCarryCookie() {
        List<Request> requests = new ArrayList<>();
        BilibiliHttpClient http = client(requests, request -> reply(request, 302, "text/plain", "")
                .newBuilder().header("Location", "https://www.bilibili.com/video/BV1xx411c7mD").build());
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", http.redirect("https://b23.tv/abc"));
        assertNull(requests.get(0).header("Cookie"));
        BilibiliHttpClient unsafe = client(new ArrayList<>(), request -> reply(request, 302, "text/plain", "")
                .newBuilder().header("Location", "https://bilibili.com.evil.example/video/test").build());
        assertThrows(IllegalArgumentException.class, () -> unsafe.redirect("https://b23.tv/abc"));
    }

    @Test
    void privateHostsUserInfoAndUnexpectedSchemesAreRejected() {
        for (String url : List.of("http://127.0.0.1/a", "https://localhost/a", "http://192.168.1.1/a", "file:///etc/passwd",
                "https://cdn.bilivideo.com.evil.example/a", "https://cdn.bilivideo.com@evil.example/a", "https://user@cdn.bilivideo.com/a",
                "https://cdn.bilivideo.com:22/a", "https://cdn.bilivideo.com/a#fragment", "https://cdn.bilivideo.com/a\r\nCookie:test")) {
            assertThrows(IllegalArgumentException.class, () -> BilibiliHttpClient.requireMediaUri(url), url);
        }
        assertEquals(8082, BilibiliHttpClient.requireMediaUri("https://cdn.bilivideo.cn:8082/a").getPort());
    }

    @Test
    void malformedCookieIsRejectedBeforeHttpHeadersCanExposeItsValue() {
        for (String cookie : List.of("SESSDATA=private\nvalue", "SESSDATA=private中文", "x".repeat(16385))) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new BilibiliHttpClient(new OkHttpClient(), new ObjectMapper(), cookie));
            assertEquals("BILIBILI_COOKIE 格式不正确", error.getMessage());
        }
    }

    @Test
    void invalidAndOversizedApiResponsesHaveSanitizedErrors() {
        BilibiliHttpClient rejected = client(new ArrayList<>(), request -> reply(request, 200, "application/json", "{\"code\":-101,\"message\":\"private-session=value\"}"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> rejected.api("https://api.bilibili.com/x", ""));
        assertTrue(ex.getMessage().contains("登录态"));
        assertFalse(ex.getMessage().contains("private-session=value"));
        BilibiliHttpClient large = client(new ArrayList<>(), request -> reply(request, 200, "application/json", "x".repeat(2 * 1024 * 1024 + 1)));
        assertTrue(assertThrows(IllegalStateException.class, () -> large.api("https://api.bilibili.com/x", "")).getMessage().contains("过大"));
        BilibiliHttpClient bad = client(new ArrayList<>(), request -> reply(request, 200, "text/html", "<html>private error</html>"));
        assertFalse(assertThrows(IllegalStateException.class, () -> bad.api("https://api.bilibili.com/x", "")).getMessage().contains("private error"));
    }

    private static BilibiliHttpClient client(List<Request> requests, Handler handler) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request());
            return handler.respond(chain.request());
        }).build();
        return new BilibiliHttpClient(client, new ObjectMapper(), "private-session=value");
    }

    static Response reply(Request request, int status, String mime, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(ResponseBody.create(body, MediaType.get(mime))).header("Content-Type", mime).build();
    }

    private interface Handler {

        Response respond(Request request);
    }
}
