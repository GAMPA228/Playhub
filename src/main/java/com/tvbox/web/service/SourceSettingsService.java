package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import okhttp3.HttpUrl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SourceSettingsService {

    private static final int MAX_URL_LENGTH = 8192;
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final Set<String> FIELDS = Set.of("configUrl", "configHistory", "liveSourceUrl", "liveEpgUrl");
    private final ObjectMapper mapper;
    private final AppPathsService paths;

    @Value("${app.data-dir:data}")
    private String dataDir;

    private Path settingsFile;

    public record Settings(String configUrl, List<String> configHistory, String liveSourceUrl,
                           String liveEpgUrl, boolean initialized) {
        public Settings {
            configHistory = List.copyOf(configHistory);
        }
    }

    public SourceSettingsService(ObjectMapper mapper, AppPathsService paths) {
        this.mapper = mapper;
        this.paths = paths;
    }

    @PostConstruct
    public void init() throws IOException {
        Path root = Path.of(dataDir);
        root = root.isAbsolute() ? root.normalize() : paths.resolveFromBase(dataDir);
        Files.createDirectories(root);
        settingsFile = root.resolve("source-settings.json");
    }

    public synchronized Settings getSettings() {
        if (Files.notExists(settingsFile)) {
            return new Settings("", List.of(), "", "", false);
        }
        try {
            if (Files.size(settingsFile) > MAX_FILE_BYTES) {
                throw new IOException("配置文件超过 512 KiB");
            }
            JsonNode stored = mapper.readTree(Files.readAllBytes(settingsFile));
            requireObject(stored);
            // initialized is derived from the existence of a valid file, not client input.
            ((ObjectNode) stored).remove("initialized");
            return merge(new Settings("", List.of(), "", "", false), stored);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("服务端数据源配置读取失败，请检查 " + settingsFile + ": " + ex.getMessage(), ex);
        }
    }

    public synchronized Settings update(JsonNode patch) {
        Settings next = merge(getSettings(), patch);
        write(next);
        return next;
    }

    public synchronized Settings saveVideoConfig(String url) {
        Settings current = getSettings();
        String validated = validateUrl(url, "configUrl");
        List<String> history = new ArrayList<>(current.configHistory());
        history.remove(validated);
        if (!validated.isEmpty()) {
            history.add(0, validated);
        }
        Settings next = new Settings(validated, history.subList(0, Math.min(history.size(), 10)),
                current.liveSourceUrl(), current.liveEpgUrl(), true);
        write(next);
        return next;
    }

    private Settings merge(Settings current, JsonNode patch) {
        requireObject(patch);
        patch.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                throw new IllegalArgumentException("不支持的数据源配置字段: " + name);
            }
        });
        return new Settings(
                readUrl(patch, "configUrl", current.configUrl()),
                patch.has("configHistory") ? readHistory(patch.get("configHistory")) : current.configHistory(),
                readUrl(patch, "liveSourceUrl", current.liveSourceUrl()),
                readUrl(patch, "liveEpgUrl", current.liveEpgUrl()), true);
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("数据源配置必须是 JSON 对象");
        }
    }

    private String readUrl(JsonNode node, String field, String fallback) {
        if (!node.has(field)) {
            return fallback;
        }
        if (!node.get(field).isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串，清空请使用空字符串");
        }
        return validateUrl(node.get(field).asText(), field);
    }

    private String validateUrl(String raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        String value = raw.trim();
        if (value.length() > MAX_URL_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 长度超过限制或包含控制字符");
        }
        if (value.isEmpty()) {
            return value;
        }
        String target = value;
        boolean video = field.equals("configUrl") || field.equals("configHistory");
        if (video) {
            target = target.split(";pk;", 2)[0];
            if (target.startsWith("clan://")) {
                target = "http://" + target.substring(7);
            } else if (!target.regionMatches(true, 0, "http://", 0, 7)
                    && !target.regionMatches(true, 0, "https://", 0, 8)
                    && !target.contains("://")) {
                target = "http://" + target;
            }
        }
        try {
            HttpUrl.get(target);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " 不是有效的 HTTP(S) 地址");
        }
        return value;
    }

    private List<String> readHistory(JsonNode node) {
        if (!node.isArray() || node.size() > 10) {
            throw new IllegalArgumentException("配置历史必须是数组，最多 10 项");
        }
        Set<String> history = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            if (!entry.isTextual()) {
                throw new IllegalArgumentException("配置历史地址必须是字符串");
            }
            String url = validateUrl(entry.asText(), "configHistory");
            if (!url.isEmpty()) {
                history.add(url);
            }
        }
        return List.copyOf(history);
    }

    private void write(Settings settings) {
        Path temporary = null;
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            temporary = Files.createTempFile(settingsFile.getParent(), ".source-settings-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("服务端数据源配置保存失败，请检查数据目录写权限", ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A failed cleanup must not obscure the actual save result.
                }
            }
        }
    }
}
