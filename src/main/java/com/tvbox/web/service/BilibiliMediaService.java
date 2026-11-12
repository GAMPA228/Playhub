package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 有时效且绑定浏览器会话的媒体代理，不接受任意未签名 URL。 */
@Service
public class BilibiliMediaService {

    private static final long MAX_LIFETIME_MS = Duration.ofHours(1).toMillis();
    private static final int MAX_PLAYBACKS = 64;
    private static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    private static final String VIDEO_REFERER = "https://www.bilibili.com/";
    private static final String LIVE_REFERER = "https://live.bilibili.com/";
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("\\bURI=\"([^\"]*)\"");
    private static final Pattern BYTE_RANGE = Pattern.compile("bytes=(\\d{0,19})-(\\d{0,19})");
    private static final Pattern SEGMENT_RANGE = Pattern.compile("\\d{1,19}-\\d{1,19}");
    private final BilibiliHttpClient http;
    private final Clock clock;
    private final LinkedHashMap<String, Context> contexts = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Semaphore streams = new Semaphore(8);

    public record Playback(String url, String format, long expiresAt) {
    }

    private static final class Context {

        private final String id;
        private final String sessionId;
        private final String format;
        private final String referer;
        private final byte[] secret;
        private final long expiresAt;
        private String manifest;

        private Context(String sessionId, String format, String referer, byte[] secret, long expiresAt) {
            this.id = UUID.randomUUID().toString();
            this.sessionId = sessionId;
            this.format = format;
            this.referer = referer;
            this.secret = secret;
            this.expiresAt = expiresAt;
        }
    }

    @Autowired
    public BilibiliMediaService(BilibiliHttpClient http) {
        this(http, Clock.systemUTC());
    }

    BilibiliMediaService(BilibiliHttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public Playback createDash(String sessionId, JsonNode video, JsonNode audio, double durationSeconds) {
        URI videoUrl = streamUri(video);
        URI audioUrl = streamUri(audio);
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > 604800) {
            throw new IllegalArgumentException("B站视频时长无效");
        }
        Context context = newContext(sessionId, "dash", VIDEO_REFERER, List.of(videoUrl, audioUrl));
        String duration = String.format(Locale.ROOT, "PT%.3fS", durationSeconds);
        context.manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
                + " profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" minBufferTime=\"PT1.5S\""
                + " mediaPresentationDuration=\"" + duration + "\"><Period start=\"PT0S\">"
                + adaptation(context, video, videoUrl, true)
                + adaptation(context, audio, audioUrl, false)
                + "</Period></MPD>";
        store(context);
        return new Playback("/api/bilibili/play/" + context.id + "/manifest.mpd", "dash", context.expiresAt);
    }

    public Playback createHls(String sessionId, String mediaUrl) {
        return createSingle(sessionId, mediaUrl, "hls", LIVE_REFERER);
    }

    public Playback createFile(String sessionId, String mediaUrl) {
        return createSingle(sessionId, mediaUrl, "mp4", VIDEO_REFERER);
    }

    public String manifest(String sessionId, String playbackId) {
        Context context = requireContext(sessionId, playbackId);
        if (!"dash".equals(context.format) || context.manifest == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有该播放清单");
        }
        return context.manifest;
    }

    public void stream(String sessionId, String playbackId, String resource, String signature,
                       String range, boolean head, HttpServletResponse response) throws IOException {
        Context context = requireContext(sessionId, playbackId);
        URI uri = signedResource(context, resource, signature);
        validateRange(range);
        if (isPlaylist(uri, "") && range != null && !range.isBlank()) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "直播清单不支持字节范围请求");
        }
        if (!streams.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "B站播放请求较多，请稍后重试");
        }
        try (Response upstream = http.media(uri, context.referer, range, head)) {
            int status = upstream.code();
            if (status == 401 || status == 403 || status == 404) {
                throw new ResponseStatusException(HttpStatus.GONE, "B站播放地址已失效，请刷新地址");
            }
            if (status != 200 && status != 206 && status != 416) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站媒体暂不可用，请刷新或稍后重试");
            }
            response.setStatus(status);
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            String contentType = upstream.header("Content-Type", "application/octet-stream");
            boolean playlist = isPlaylist(uri, contentType);
            if (playlist && status == 206) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站直播清单返回了不完整内容，请刷新地址");
            }
            response.setContentType(playlist ? "application/vnd.apple.mpegurl; charset=UTF-8" : safeContentType(contentType));
            if (!head && playlist && status == 200 && upstream.body() != null) {
                byte[] bytes = upstream.body().byteStream().readNBytes(MAX_PLAYLIST_BYTES + 1);
                if (bytes.length > MAX_PLAYLIST_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站直播清单过大");
                }
                URI finalUri = upstream.request().url().uri();
                String rewritten = rewritePlaylist(context, finalUri, new String(bytes, StandardCharsets.UTF_8));
                response.getOutputStream().write(rewritten.getBytes(StandardCharsets.UTF_8));
                return;
            }
            for (String name : List.of("Content-Range", "Accept-Ranges", "Content-Length")) {
                String value = upstream.header(name);
                if (value != null) {
                    response.setHeader(name, value);
                }
            }
            if (!head && upstream.body() != null) {
                upstream.body().byteStream().transferTo(response.getOutputStream());
            }
        } catch (AsyncRequestNotUsableException ex) {
            // Seeking, switching streams or leaving the page can close the browser connection.
            // Its response is no longer writable; do not try to append a JSON error to media.
            return;
        } catch (IOException ex) {
            if (response.isCommitted()) {
                throw ex;
            }
            response.reset();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "连接 B站媒体失败或超时，请刷新地址");
        } catch (IllegalArgumentException ex) {
            if (!response.isCommitted()) {
                response.reset();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站媒体跳转到了不受支持的地址");
        } finally {
            streams.release();
        }
    }

    private Playback createSingle(String sessionId, String mediaUrl, String format, String referer) {
        URI uri = BilibiliHttpClient.requireMediaUri(mediaUrl);
        Context context = newContext(sessionId, format, referer, List.of(uri));
        String url = proxyUrl(context, uri);
        store(context);
        return new Playback(url, format, context.expiresAt);
    }

    private Context newContext(String sessionId, String format, String referer, List<URI> mediaUrls) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少浏览器会话");
        }
        long now = clock.millis();
        long expiresAt = now + MAX_LIFETIME_MS;
        for (URI uri : mediaUrls) {
            expiresAt = Math.min(expiresAt, upstreamExpiry(uri, expiresAt));
        }
        if (expiresAt <= now + 1000) {
            throw new IllegalStateException("B站返回的播放地址已失效，请重新解析");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return new Context(sessionId, format, referer, secret, expiresAt);
    }

    private synchronized void store(Context context) {
        contexts.values().removeIf(item -> item.expiresAt <= clock.millis());
        while (contexts.size() >= MAX_PLAYBACKS) {
            contexts.remove(contexts.keySet().iterator().next());
        }
        contexts.put(context.id, context);
    }

    private synchronized Context requireContext(String sessionId, String playbackId) {
        Context context = contexts.get(playbackId);
        if (context == null || !context.sessionId.equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "播放会话不存在，请重新解析");
        }
        if (context.expiresAt <= clock.millis()) {
            contexts.remove(playbackId);
            throw new ResponseStatusException(HttpStatus.GONE, "播放地址已过期，请刷新地址");
        }
        return context;
    }

    private String adaptation(Context context, JsonNode track, URI uri, boolean video) {
        JsonNode segment = track.has("SegmentBase") ? track.path("SegmentBase") : track.path("segment_base");
        String initialization = segment.path("Initialization").asText(segment.path("initialization").asText());
        String index = segment.path("indexRange").asText(segment.path("index_range").asText());
        if (!SEGMENT_RANGE.matcher(initialization).matches() || !SEGMENT_RANGE.matcher(index).matches()) {
            throw new IllegalStateException("B站分轨视频缺少有效的分段索引");
        }
        String codecs = track.path("codecs").asText();
        if (codecs.isBlank() || codecs.length() > 100 || !codecs.matches("[a-zA-Z0-9.,_-]+")) {
            throw new IllegalStateException("B站视频编码信息无效");
        }
        String mime = video ? "video/mp4" : "audio/mp4";
        String representation = "<AdaptationSet contentType=\"" + (video ? "video" : "audio")
                + "\" mimeType=\"" + mime + "\" segmentAlignment=\"true\"><Representation id=\""
                + (video ? "v" : "a") + track.path("id").asInt() + "\" bandwidth=\""
                + Math.max(1, track.path("bandwidth").asLong(1)) + "\" codecs=\"" + codecs + "\"";
        if (video) {
            representation += " width=\"" + Math.max(1, track.path("width").asInt(1)) + "\" height=\""
                    + Math.max(1, track.path("height").asInt(1)) + "\"";
        }
        return representation + "><BaseURL>" + xml(proxyUrl(context, uri)) + "</BaseURL>"
                + "<SegmentBase indexRange=\"" + index + "\"><Initialization range=\"" + initialization
                + "\"/></SegmentBase></Representation></AdaptationSet>";
    }

    private static URI streamUri(JsonNode track) {
        if (track == null || !track.isObject()) {
            throw new IllegalStateException("B站没有返回完整音视频轨道");
        }
        return BilibiliHttpClient.requireMediaUri(track.path("baseUrl").asText(track.path("base_url").asText()));
    }

    private String rewritePlaylist(Context context, URI base, String text) {
        String playlist = text.replace("\uFEFF", "");
        if (!playlist.stripLeading().startsWith("#EXTM3U")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站未返回有效的直播清单");
        }
        StringBuilder rewritten = new StringBuilder();
        for (String line : playlist.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                rewritten.append(proxyUrl(context, resolveResource(base, trimmed)));
            } else if (trimmed.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) {
                    URI uri = resolveResource(base, matcher.group(1));
                    matcher.appendReplacement(replaced, Matcher.quoteReplacement("URI=\"" + proxyUrl(context, uri) + "\""));
                }
                matcher.appendTail(replaced);
                rewritten.append(replaced);
            }
            rewritten.append('\n');
        }
        return rewritten.toString();
    }

    private URI resolveResource(URI base, String reference) {
        try {
            return BilibiliHttpClient.requireMediaUri(base.resolve(reference).toString());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站直播清单包含不受支持的媒体地址");
        }
    }

    private String proxyUrl(Context context, URI uri) {
        String resource = Base64.getUrlEncoder().withoutPadding().encodeToString(uri.toString().getBytes(StandardCharsets.UTF_8));
        String fileName = uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8") ? "index.m3u8" : "media.bin";
        return "/api/bilibili/media/" + context.id + "/" + fileName + "?r=" + resource + "&sig=" + signature(context, resource);
    }

    private URI signedResource(Context context, String resource, String supplied) {
        if (resource == null || resource.length() > 24000 || supplied == null || supplied.length() > 100
                || !MessageDigest.isEqual(signature(context, resource).getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "播放资源签名无效");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(resource), StandardCharsets.UTF_8);
            return BilibiliHttpClient.requireMediaUri(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "播放资源地址无效");
        }
    }

    private String signature(Context context, String resource) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(context.secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(resource.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成播放资源签名");
        }
    }

    private static void validateRange(String range) {
        if (range == null || range.isBlank()) {
            return;
        }
        Matcher matcher = BYTE_RANGE.matcher(range);
        if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "只支持单个有效字节范围");
        }
        try {
            long start = matcher.group(1).isEmpty() ? -1 : Long.parseLong(matcher.group(1));
            long end = matcher.group(2).isEmpty() ? -1 : Long.parseLong(matcher.group(2));
            if ((start >= 0 && end >= 0 && end < start) || (start < 0 && end <= 0)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "字节范围无效");
        }
    }

    private static long upstreamExpiry(URI uri, long fallback) {
        String query = uri.getRawQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || !(parts[0].equals("deadline") || parts[0].equals("expires") || parts[0].equals("expire"))) {
                continue;
            }
            try {
                long seconds = Long.parseLong(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                if (seconds > 1_000_000_000L && seconds < 10_000_000_000L) {
                    return Math.min(fallback, seconds * 1000 - 15000);
                }
            } catch (IllegalArgumentException ignored) {
                // CDN 的非时间戳参数不用于估算过期时间。
            }
        }
        return fallback;
    }

    private static boolean isPlaylist(URI uri, String contentType) {
        return uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")
                || contentType.toLowerCase(Locale.ROOT).contains("mpegurl");
    }

    private static String safeContentType(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("video/") || lower.startsWith("audio/") || lower.startsWith("application/octet-stream")) {
            return contentType;
        }
        return "application/octet-stream";
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
