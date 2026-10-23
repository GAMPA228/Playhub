package com.tvbox.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tvbox.web.model.ConfigPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfigServiceTest {

    private static final String SESSION = "idn-browser";
    private static final String CONFIG = """
            {"spider":"./spider.jar","sites":[{"key":"test","name":"test","type":1,"api":"./api.json"}]}
            """;
    private ConfigService service;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        service = new ConfigService(new ObjectMapper(), mock(AppPathsService.class));
        client = mock(HttpClient.class);
        ReflectionTestUtils.setField(service, "httpClient", client);
    }

    static Stream<Arguments> configurationAddresses() {
        return Stream.of(
                Arguments.of("http://www.饭太硬.top/tv/", "http://www.xn--sss604efuw.top/tv/"),
                Arguments.of("https://www.饭太硬.top/tv/", "https://www.xn--sss604efuw.top/tv/"),
                Arguments.of("www.饭太硬.top/tv/", "http://www.xn--sss604efuw.top/tv/"),
                Arguments.of("  http://www.饭太硬.top/tv/  ", "http://www.xn--sss604efuw.top/tv/"),
                Arguments.of("HTTPS://WWW.饭太硬.TOP/tv/", "https://www.xn--sss604efuw.top/tv/"),
                Arguments.of("http://www。饭太硬。top/tv/", "http://www.xn--sss604efuw.top/tv/"),
                Arguments.of("http://www.xn--sss604efuw.top/tv/", "http://www.xn--sss604efuw.top/tv/"),
                Arguments.of("https://www.饭太硬.top:8443/tv/config.json", "https://www.xn--sss604efuw.top:8443/tv/config.json"),
                Arguments.of("https://www.饭太硬.top/配置/订阅.json?wd=中文&token=a%2Bb+c",
                        "https://www.xn--sss604efuw.top/%E9%85%8D%E7%BD%AE/%E8%AE%A2%E9%98%85.json?wd=%E4%B8%AD%E6%96%87&token=a%2Bb+c"),
                Arguments.of("https://example.com/a%2Fb/config.json?token=x%2Fy%3Dz%26k&v=a+b#part",
                        "https://example.com/a%2Fb/config.json?token=x%2Fy%3Dz%26k&v=a+b#part"),
                Arguments.of("https://www.饭太硬.top/配置 名称.json?wd=中文 空格",
                        "https://www.xn--sss604efuw.top/%E9%85%8D%E7%BD%AE%20%E5%90%8D%E7%A7%B0.json?wd=%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC"),
                Arguments.of("https://user:p%40ss@饭太硬.top:8443/a%2Fb.json?token=a%2Bb",
                        "https://user:p%40ss@xn--sss604efuw.top:8443/a%2Fb.json?token=a%2Bb"),
                Arguments.of("http://127.0.0.1:18080/config.json", "http://127.0.0.1:18080/config.json"),
                Arguments.of("http://[::1]:18080/config.json", "http://[::1]:18080/config.json"),
                Arguments.of("clan://localhost/配置.json", "http://127.0.0.1:9978/file/%E9%85%8D%E7%BD%AE.json"),
                Arguments.of("clan://192.168.1.20/config.json", "http://192.168.1.20/file/config.json"),
                Arguments.of("http://www.饭太硬.top/tv/;pk;test-key", "http://www.xn--sss604efuw.top/tv/"));
    }

    @ParameterizedTest
    @MethodSource("configurationAddresses")
    void loadConfigSendsNormalizedUriAndPreservesOriginalAddress(String input, String expected) throws Exception {
        stubResponse(CONFIG);

        ConfigPayload config = service.loadConfig(SESSION, input);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals(expected, request.getValue().uri().toString());
        assertNotNull(request.getValue().uri().getHost());
        assertEquals("okhttp/3.15", request.getValue().headers().firstValue("User-Agent").orElseThrow());
        assertEquals(input.trim(), service.getConfigUrl(SESSION));
        String base = expected.substring(0, expected.lastIndexOf('/') + 1);
        assertEquals(base + "spider.jar", config.getSpider());
        assertEquals(base + "api.json", config.getSites().get(0).getApi());
        assertEquals("s0", config.getSites().get(0).getUid());
        assertEquals(config, service.getPayload(SESSION));
    }

    static Stream<String> invalidAddresses() {
        return Stream.of("", "   ", "http://", "http://bad host/config.json", "http://example.com:99999/config.json");
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void invalidAddressIsRejectedBeforeSendingRequest(String input) {
        assertThrows(IllegalArgumentException.class, () -> service.loadConfig(SESSION, input));
        verifyNoInteractions(client);
    }

    @Test
    void networkFailureRemainsAnErrorAfterIdnConversion() throws Exception {
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("HTTP/1.1 header parser received no bytes"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.loadConfig(SESSION, "http://www.饭太硬.top/tv/"));

        assertEquals("拉取配置失败: HTTP/1.1 header parser received no bytes", error.getMessage());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("www.xn--sss604efuw.top", request.getValue().uri().getHost());
        assertNull(service.getConfigUrl(SESSION));
    }

    @Test
    void realHttpLoadPreservesEncodedParametersAndUnicodePath() throws Exception {
        AtomicReference<String> receivedTarget = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedTarget.set(exchange.getRequestURI().toASCIIString());
            byte[] body = CONFIG.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ConfigService realService = new ConfigService(new ObjectMapper(), mock(AppPathsService.class));
            String address = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/配置/订阅.json?token=a%2Bb+c&wd=中文";

            ConfigPayload config = realService.loadConfig(SESSION, address);

            assertEquals("/%E9%85%8D%E7%BD%AE/%E8%AE%A2%E9%98%85.json?token=a%2Bb+c&wd=%E4%B8%AD%E6%96%87",
                    receivedTarget.get());
            assertEquals(address, realService.getConfigUrl(SESSION));
            assertEquals("test", config.getSites().get(0).getKey());
            assertTrue(config.getSpider().endsWith("/%E9%85%8D%E7%BD%AE/spider.jar"));
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
    }
}
