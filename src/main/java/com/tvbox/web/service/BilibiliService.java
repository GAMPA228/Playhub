package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BilibiliService {

    private static final String VIDEO_REFERER = "https://www.bilibili.com/";
    private static final String LIVE_REFERER = "https://live.bilibili.com/";
    private static final Set<String> VIDEO_HOSTS = Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com");
    private static final Set<String> SHORT_HOSTS = Set.of("b23.tv", "bili2233.cn");
    private static final Pattern VIDEO_ID = Pattern.compile("(?i)^(BV[0-9a-z]{10}|av[0-9]{1,20})$");
    private static final Pattern VIDEO_PATH = Pattern.compile("(?i)^/video/(BV[0-9a-z]{10}|av[0-9]{1,20})/?$");
    private static final Pattern WEB_URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private final BilibiliHttpClient http;
    private final BilibiliMediaService media;
    private final Semaphore resolveSlots = new Semaphore(2);

    public BilibiliService(BilibiliHttpClient http, BilibiliMediaService media) {
        this.http = http;
        this.media = media;
    }

    public record VideoPage(int page, String title, int duration) {
    }

    public record Quality(int id, String label) {
    }

    public record Result(boolean ok, String kind, String status, String title, String author,
                         String cover, String sourceUrl, int page, List<VideoPage> pages,
                         int quality, List<Quality> qualities, String notice,
                         BilibiliMediaService.Playback playback) {
    }

    record Link(boolean live, String id, int page) {
    }

    public Result resolve(String sessionId, String input, Integer page, Integer quality) {
        if (!resolveSlots.tryAcquire()) {
            throw new IllegalStateException("B站解析繁忙，请稍后重试");
        }
        try {
            Link link = identify(input);
            if (quality != null && (quality < 1 || quality > 30000)) {
                throw new IllegalArgumentException("清晰度参数无效");
            }
            if (page != null && (page < 1 || page > 1000)) {
                throw new IllegalArgumentException("分 P 参数无效");
            }
            return link.live() ? resolveLive(sessionId, link.id(), quality)
                    : resolveVideo(sessionId, link.id(), page == null ? link.page() : page, quality);
        } finally {
            resolveSlots.release();
        }
    }

    Link identify(String input) {
        if (input == null || input.isBlank() || input.length() > 4096) {
            throw new IllegalArgumentException("请输入 B站视频链接、BV/av 号或直播间链接/房间号");
        }
        String value = input.trim();
        if (VIDEO_ID.matcher(value).matches()) {
            return new Link(false, normalizeVideoId(value), 1);
        }
        if (value.matches("[0-9]{1,15}")) {
            return new Link(true, value, 1);
        }
        Matcher matcher = WEB_URL.matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("暂仅支持 B站普通视频和直播间，请输入完整链接或 BV/av 号");
        }
        String url = matcher.group().replaceAll("[。，；！？、）),.;]+$", "");
        for (int hop = 0; hop <= 5; hop++) {
            URI uri = officialUri(url);
            String host = uri.getHost().toLowerCase();
            if (SHORT_HOSTS.contains(host)) {
                if (hop == 5) {
                    throw new IllegalArgumentException("B站短链接跳转过多，请粘贴最终视频或直播间链接");
                }
                String redirect = http.redirect(uri.toString());
                if (redirect == null || redirect.isBlank()) {
                    throw new IllegalArgumentException("无法展开 B站短链接，请粘贴最终视频或直播间链接");
                }
                url = uri.resolve(redirect).toString();
                continue;
            }
            String path = uri.getPath();
            if ("live.bilibili.com".equals(host)) {
                if (path != null && path.matches("/[0-9]{1,15}/?")) {
                    return new Link(true, path.replace("/", ""), 1);
                }
                throw new IllegalArgumentException("请使用 https://live.bilibili.com/房间号 格式的直播链接");
            }
            Matcher id = VIDEO_PATH.matcher(path == null ? "" : path);
            if (id.matches()) {
                return new Link(false, normalizeVideoId(id.group(1)), pageFromQuery(uri));
            }
            throw new IllegalArgumentException("暂不支持此 B站链接，请使用普通投稿视频；番剧、付费课堂和动态链接暂不支持");
        }
        throw new IllegalArgumentException("无法识别 B站链接");
    }

    private URI officialUri(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || !(uri.getPort() == -1 || uri.getPort() == 443 || uri.getPort() == 80)
                    || !(VIDEO_HOSTS.contains(host) || SHORT_HOSTS.contains(host) || "live.bilibili.com".equals(host))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("链接域名无效，暂仅支持 B站官方视频、直播与分享短链接");
        }
    }

    private String normalizeVideoId(String id) {
        return id.regionMatches(true, 0, "BV", 0, 2) ? "BV" + id.substring(2) : "av" + id.substring(2);
    }

    private int pageFromQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if ("p".equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                    try {
                        int page = Integer.parseInt(URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
                        if (page >= 1 && page <= 1000) {
                            return page;
                        }
                    } catch (NumberFormatException ignored) {
                        // Invalid page numbers must not silently play a different part.
                    }
                    throw new IllegalArgumentException("链接中的分 P 参数无效");
                }
            }
        }
        return 1;
    }

    private Result resolveVideo(String sessionId, String id, int page, Integer requestedQuality) {
        String parameter = id.startsWith("BV") ? "bvid=" + encode(id) : "aid=" + id.substring(2);
        JsonNode info = http.api("https://api.bilibili.com/x/web-interface/view?" + parameter, VIDEO_REFERER).path("data");
        String bvid = info.path("bvid").asText();
        if (!VIDEO_ID.matcher(bvid).matches() || !bvid.startsWith("BV")) {
            throw new IllegalStateException("B站未返回有效的视频信息，内容可能已删除或不可访问");
        }
        List<VideoPage> pages = new ArrayList<>();
        JsonNode selected = null;
        for (JsonNode item : info.path("pages")) {
            int number = item.path("page").asInt();
            pages.add(new VideoPage(number, item.path("part").asText("第 " + number + " P"), item.path("duration").asInt()));
            if (number == page) {
                selected = item;
            }
        }
        if (selected == null || selected.path("cid").asLong() <= 0) {
            throw new IllegalArgumentException("该视频不存在第 " + page + " P，请选择有效分 P");
        }
        String base = "https://api.bilibili.com/x/player/playurl?bvid=" + encode(bvid)
                + "&cid=" + selected.path("cid").asLong() + "&qn=" + (requestedQuality == null ? 80 : requestedQuality);
        JsonNode play = http.api(base + "&fnval=4048&fnver=0&fourk=1", VIDEO_REFERER).path("data");
        List<JsonNode> videoTracks = new ArrayList<>();
        for (JsonNode track : play.path("dash").path("video")) {
            if (track.path("codecs").asText().startsWith("avc1")) {
                videoTracks.add(track);
            }
        }
        videoTracks.sort(Comparator.comparingInt((JsonNode track) -> track.path("id").asInt()).reversed());
        JsonNode audio = null;
        for (JsonNode track : play.path("dash").path("audio")) {
            if (track.path("codecs").asText().startsWith("mp4a")
                    && (audio == null || track.path("bandwidth").asLong() > audio.path("bandwidth").asLong())) {
                audio = track;
            }
        }
        BilibiliMediaService.Playback playback;
        int actualQuality;
        List<Quality> qualities = new ArrayList<>();
        if (!videoTracks.isEmpty() && audio != null) {
            JsonNode video = videoTracks.get(0);
            if (requestedQuality != null) {
                video = videoTracks.stream().filter(track -> track.path("id").asInt() == requestedQuality)
                        .findFirst().orElse(video);
            }
            actualQuality = video.path("id").asInt();
            Map<Integer, Quality> unique = new LinkedHashMap<>();
            for (JsonNode track : videoTracks) {
                int qn = track.path("id").asInt();
                unique.putIfAbsent(qn, new Quality(qn, qualityLabel(qn)));
            }
            qualities.addAll(unique.values());
            double duration = play.path("timelength").asDouble() / 1000.0;
            if (duration <= 0) {
                duration = selected.path("duration").asDouble();
            }
            playback = media.createDash(sessionId, preferredCdn(video), preferredCdn(audio), duration);
        } else {
            // Only muxed MP4 is a valid fallback: never claim success with a silent video-only track.
            JsonNode progressive = http.api(base + "&fnval=1&platform=html5&high_quality=1", VIDEO_REFERER).path("data");
            JsonNode files = progressive.path("durl");
            if (!files.isArray() || files.size() != 1 || !progressive.path("format").asText().contains("mp4")) {
                throw new IllegalStateException("B站未返回浏览器可播放的有声视频；该内容可能受限或需要有效登录态");
            }
            playback = media.createFile(sessionId, preferredUrl(files.get(0), "url", "backup_url"));
            actualQuality = progressive.path("quality").asInt(16);
            qualities.add(new Quality(actualQuality, qualityLabel(actualQuality)));
        }
        String notice = requestedQuality != null && requestedQuality != actualQuality
                ? "所选清晰度不可用，已使用 " + qualityLabel(actualQuality) + "；可用画质取决于登录态与账号权限。"
                : "可用清晰度取决于账号权限；如需登录画质，可由管理员配置服务端 BILIBILI_COOKIE。";
        return new Result(true, "video", "ready", info.path("title").asText("B站视频"),
                info.path("owner").path("name").asText(), safeCover(info.path("pic").asText()),
                VIDEO_REFERER + "video/" + bvid, page, List.copyOf(pages), actualQuality,
                List.copyOf(qualities), notice, playback);
    }

    private Result resolveLive(String sessionId, String roomId, Integer requestedQuality) {
        JsonNode room = http.api("https://api.live.bilibili.com/room/v1/Room/room_init?id=" + roomId, LIVE_REFERER).path("data");
        long realRoom = room.path("room_id").asLong();
        if (realRoom <= 0 || room.path("is_hidden").asBoolean() || room.path("is_locked").asBoolean()
                || (room.path("encrypted").asBoolean() && !room.path("pwd_verified").asBoolean())) {
            throw new IllegalStateException("该直播间不存在或当前不可访问");
        }
        JsonNode info = http.api("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + realRoom, LIVE_REFERER).path("data");
        String sourceUrl = LIVE_REFERER + realRoom;
        String title = info.path("title").asText("B站直播间 " + realRoom);
        String cover = safeCover(info.path("user_cover").asText(info.path("keyframe").asText()));
        String author = "主播 UID " + room.path("uid").asLong();
        if (room.path("live_status").asInt() != 1) {
            boolean loop = room.path("live_status").asInt() == 2;
            return new Result(true, "live", loop ? "loop" : "offline", title, author, cover, sourceUrl,
                    1, List.of(), 0, List.of(), loop ? "直播间正在轮播，尚未开播，可稍后重新检查。" : "主播尚未开播，可稍后重新检查。", null);
        }
        int qn = requestedQuality == null ? 10000 : requestedQuality;
        JsonNode play = http.api("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id="
                + realRoom + "&protocol=1&format=0,1,2&codec=0&qn=" + qn + "&platform=web&ptype=8", LIVE_REFERER).path("data");
        if (play.has("live_status") && play.path("live_status").asInt() != 1) {
            boolean loop = play.path("live_status").asInt() == 2;
            return new Result(true, "live", loop ? "loop" : "offline", title, author, cover, sourceUrl,
                    1, List.of(), 0, List.of(), "主播已下播或切换到轮播，可稍后重新检查。", null);
        }
        JsonNode playurl = play.path("playurl_info").path("playurl");
        JsonNode selected = null;
        for (JsonNode stream : playurl.path("stream")) {
            if (!"http_hls".equals(stream.path("protocol_name").asText())) {
                continue;
            }
            for (JsonNode format : stream.path("format")) {
                for (JsonNode codec : format.path("codec")) {
                    if ("avc".equals(codec.path("codec_name").asText()) && !codec.path("drm").asBoolean()
                            && !codec.path("url_info").isEmpty()) {
                        selected = codec;
                        if ("ts".equals(format.path("format_name").asText())) {
                            break;
                        }
                    }
                }
                if (selected != null) {
                    break;
                }
            }
            if (selected != null) {
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("直播间未返回浏览器可播放的 HLS 流，请刷新或稍后重试");
        }
        List<String> candidates = new ArrayList<>();
        for (JsonNode item : selected.path("url_info")) {
            candidates.add(item.path("host").asText() + selected.path("base_url").asText() + item.path("extra").asText());
        }
        String url = chooseUrl(candidates);
        List<Quality> qualities = new ArrayList<>();
        for (JsonNode accepted : selected.path("accept_qn")) {
            int id = accepted.asInt();
            String label = "清晰度 " + id;
            for (JsonNode description : playurl.path("g_qn_desc")) {
                if (description.path("qn").asInt() == id) {
                    label = description.path("desc").asText(label);
                    break;
                }
            }
            qualities.add(new Quality(id, label));
        }
        int actual = selected.path("current_qn").asInt();
        return new Result(true, "live", "ready", title, author, cover, sourceUrl, 1, List.of(), actual,
                List.copyOf(qualities), "直播地址有时效性；播放失败时可点击刷新地址重新获取。", media.createHls(sessionId, url));
    }

    private JsonNode preferredCdn(JsonNode track) {
        ObjectNode copy = (ObjectNode) track.deepCopy();
        copy.put("baseUrl", preferredUrl(track, track.hasNonNull("baseUrl") ? "baseUrl" : "base_url",
                track.has("backupUrl") ? "backupUrl" : "backup_url"));
        return copy;
    }

    private String preferredUrl(JsonNode node, String primary, String backups) {
        List<String> candidates = new ArrayList<>();
        candidates.add(node.path(primary).asText());
        for (JsonNode url : node.path(backups)) {
            candidates.add(url.asText());
        }
        return chooseUrl(candidates);
    }

    private String chooseUrl(List<String> candidates) {
        List<URI> valid = new ArrayList<>();
        for (String value : candidates) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                valid.add(BilibiliHttpClient.requireMediaUri(value));
            } catch (IllegalArgumentException ignored) {
                // An unusable backup must not invalidate an otherwise supported CDN.
            }
        }
        return valid.stream()
                .sorted(Comparator.comparingInt((URI uri) -> uri.getHost().contains("mcdn") ? 1 : 0)
                        .thenComparingInt(uri -> "https".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == -1 ? 0 : 1))
                .findFirst().orElseThrow(() -> new IllegalStateException("B站未返回有效的媒体地址")).toString();
    }

    private String safeCover(String value) {
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("http://")) {
            value = "https://" + value.substring(7);
        }
        try {
            URI uri = BilibiliHttpClient.requireMediaUri(value);
            return uri.toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String qualityLabel(int value) {
        return switch (value) {
            case 6 -> "240P";
            case 16 -> "360P";
            case 32 -> "480P";
            case 64 -> "720P";
            case 74 -> "720P 60帧";
            case 80 -> "1080P";
            case 112 -> "1080P 高码率";
            case 116 -> "1080P 60帧";
            case 120 -> "4K";
            case 125 -> "HDR";
            case 126 -> "杜比视界";
            case 127 -> "8K";
            default -> "清晰度 " + value;
        };
    }
}
