package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** B站专用请求边界；账号 Cookie 只发给固定 API，不会跟随短链或媒体重定向。 */
@Component
public class BilibiliHttpClient {

    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final Set<String> API_HOSTS = Set.of("api.bilibili.com", "api.live.bilibili.com");
    private static final Set<String> PAGE_HOSTS = Set.of("b23.tv", "bili2233.cn", "bilibili.com", "www.bilibili.com", "m.bilibili.com", "live.bilibili.com");
    private static final Set<Integer> MEDIA_PORTS = Set.of(-1, 80, 443, 8082, 4483);
    private final OkHttpClient client;
    private final OkHttpClient mediaClient;
    private final ObjectMapper mapper;
    private final String cookie;

    @Autowired
    public BilibiliHttpClient(ObjectMapper mapper, @Value("${BILIBILI_COOKIE:}") String cookie) {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(30))
                .build(), mapper, cookie);
    }

    BilibiliHttpClient(OkHttpClient client, ObjectMapper mapper, String cookie) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        this.mediaClient = this.client.newBuilder().callTimeout(Duration.ZERO).build();
        this.mapper = mapper;
        this.cookie = cookie == null ? "" : cookie.trim();
        if (this.cookie.length() > 16384 || this.cookie.chars().anyMatch(value -> value < 32 || value > 126)) {
            throw new IllegalStateException("BILIBILI_COOKIE 格式不正确");
        }
    }

    public JsonNode api(String url, String referer) {
        URI uri = requireWebUri(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !API_HOSTS.contains(lowerHost(uri))
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("不是受支持的 B站 API 地址");
        }
        Request.Builder request = request(uri, referer);
        if (!cookie.isBlank()) {
            request.header("Cookie", cookie);
        }
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("B站接口暂不可用（HTTP " + response.code() + "），请稍后重试");
            }
            byte[] bytes = response.body().byteStream().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new IllegalStateException("B站接口响应过大");
            }
            JsonNode root;
            try {
                root = mapper.readTree(bytes);
            } catch (IOException ex) {
                throw new IllegalStateException("B站未返回有效的视频数据，请稍后重试");
            }
            if (root == null || !root.has("code")) {
                throw new IllegalStateException("B站接口响应格式已变化");
            }
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String hint = switch (code) {
                    case -101 -> "登录态无效或已过期，请检查 BILIBILI_COOKIE";
                    case -403, -412, -352 -> "本次请求受到平台限制，请稍后重试";
                    case -404, 62002, 62004 -> "内容不存在、已删除或不可见";
                    default -> "无法获取该内容，可能需要登录或没有观看权限";
                };
                throw new IllegalStateException("B站返回错误 " + code + "：" + hint);
            }
            return root;
        } catch (IOException ex) {
            throw new IllegalStateException("连接 B站接口失败或超时，请稍后重试");
        }
    }

    public String redirect(String url) {
        URI uri = requirePageUri(url);
        try (Response response = client.newCall(request(uri, "https://www.bilibili.com/").build()).execute()) {
            if (!isRedirect(response.code())) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("B站分享链接访问失败（HTTP " + response.code() + "）");
                }
                return null;
            }
            String location = response.header("Location");
            if (location == null || location.isBlank()) {
                throw new IllegalStateException("B站分享链接未返回跳转地址");
            }
            return requirePageUri(uri.resolve(location).toString()).toString();
        } catch (IOException ex) {
            throw new IllegalStateException("展开 B站分享链接失败或超时");
        }
    }

    public Response media(URI uri, String referer, String range, boolean head) throws IOException {
        URI current = requireMediaUri(uri.toString());
        for (int attempt = 0; attempt <= 4; attempt++) {
            Request.Builder builder = request(current, referer);
            if (range != null && !range.isBlank()) {
                builder.header("Range", range);
            }
            builder.method(head ? "HEAD" : "GET", null);
            Response response = mediaClient.newCall(builder.build()).execute();
            if (!isRedirect(response.code())) {
                return response;
            }
            try (response) {
                String location = response.header("Location");
                if (location == null || location.isBlank() || attempt == 4) {
                    throw new IOException("B站媒体重定向无效");
                }
                current = requireMediaUri(current.resolve(location).toString());
            }
        }
        throw new IOException("B站媒体重定向次数过多");
    }

    public static URI requireMediaUri(String raw) {
        URI uri = requireWebUri(raw);
        String host = lowerHost(uri);
        boolean allowed = domain(host, "bilivideo.com") || domain(host, "bilivideo.cn")
                || domain(host, "hdslb.com") || host.equals("upos-hz-mirrorakam.akamaized.net");
        if (!allowed || !MEDIA_PORTS.contains(uri.getPort())) {
            throw new IllegalArgumentException("媒体地址不属于受支持的 B站 CDN");
        }
        return uri;
    }

    private static URI requirePageUri(String raw) {
        URI uri = requireWebUri(raw);
        if (!PAGE_HOSTS.contains(lowerHost(uri)) || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) {
            throw new IllegalArgumentException("分享链接跳转到了非 B站地址，已停止访问");
        }
        return uri;
    }

    private static URI requireWebUri(String raw) {
        try {
            if (raw == null || raw.length() > 16384 || raw.chars().anyMatch(c -> c <= 32 || c == 127)) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(raw);
            if ((!("https".equalsIgnoreCase(uri.getScheme())) && !("http".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("B站地址格式不正确或不受支持");
        }
    }

    private Request.Builder request(URI uri, String referer) {
        return new Request.Builder().url(uri.toString())
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer == null ? "https://www.bilibili.com/" : referer);
    }

    private static boolean domain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String lowerHost(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }
}
