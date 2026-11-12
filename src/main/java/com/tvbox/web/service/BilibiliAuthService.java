package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/** 单独保存共享登录态；对外仅提供配置状态，不提供读取凭证的接口。 */
@Service
public class BilibiliAuthService {

    private static final int MAX_COOKIE_LENGTH = 16384;
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private final ObjectMapper mapper;
    private final Path file;
    private final String environmentCookie;
    private State state;

    public record Status(boolean configured, String source, String updatedAt) {
    }

    private static final class State {
        private final String cookie;
        private final String source;
        private final String updatedAt;

        private State(String cookie, String source, String updatedAt) {
            this.cookie = cookie;
            this.source = source;
            this.updatedAt = updatedAt;
        }
    }

    @Autowired
    public BilibiliAuthService(ObjectMapper mapper, AppPathsService paths,
                               @Value("${app.data-dir:data}") String dataDir,
                               @Value("${BILIBILI_COOKIE:}") String environmentCookie) {
        this(mapper, paths.resolveFromBase(dataDir), environmentCookie);
    }

    BilibiliAuthService(ObjectMapper mapper, Path dataDir, String environmentCookie) {
        this.mapper = mapper;
        this.file = dataDir.resolve("bilibili-auth.json");
        this.environmentCookie = environmentCookie == null ? "" : environmentCookie;
    }

    public synchronized Status status() {
        return status(load());
    }

    public synchronized String currentCookie() {
        return load().cookie;
    }

    public synchronized Status update(String raw) {
        String cookie = normalizeCookie(raw);
        State next = new State(cookie, "saved", Instant.now().toString());
        write(next);
        // Publish only after the durable write succeeds. Empty is an explicit anonymous override.
        state = next;
        return status(next);
    }

    static String normalizeCookie(String raw) {
        if (raw == null || raw.length() > MAX_COOKIE_LENGTH
                || raw.chars().anyMatch(value -> value < 32 || value > 126)) {
            throw new IllegalArgumentException("B站 Cookie 格式无效：请粘贴单行 Cookie 值，最多 16384 个 ASCII 字符");
        }
        return raw.trim();
    }

    private Status status(State value) {
        return new Status(!value.cookie.isEmpty(), value.source, value.updatedAt);
    }

    private State load() {
        if (state != null) {
            return state;
        }
        try {
            if (Files.notExists(file)) {
                String cookie = normalizeCookie(environmentCookie);
                state = new State(cookie, cookie.isEmpty() ? "anonymous" : "environment", null);
            } else {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    throw new IOException();
                }
                JsonNode stored = mapper.readTree(Files.readAllBytes(file));
                if (stored == null || !stored.isObject() || !stored.path("cookie").isTextual()
                        || !stored.path("updatedAt").isTextual()) {
                    throw new IOException();
                }
                String cookie = normalizeCookie(stored.path("cookie").asText());
                String updatedAt = Instant.parse(stored.path("updatedAt").asText()).toString();
                state = new State(cookie, "saved", updatedAt);
            }
            return state;
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException ex) {
            // JSON parsing errors can contain the offending secret: never expose their cause/message.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "B站登录态读取失败，请检查数据目录、环境变量，或在设置中重新保存 Cookie");
        }
    }

    private void write(State next) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            ObjectNode stored = mapper.createObjectNode();
            stored.put("cookie", next.cookie);
            stored.put("updatedAt", next.updatedAt);
            byte[] bytes = mapper.writeValueAsBytes(stored);
            boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
            temporary = posix
                    ? Files.createTempFile(file.getParent(), ".bilibili-auth-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(file.getParent(), ".bilibili-auth-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "B站登录态保存失败，请检查数据目录写权限；原登录态未更改");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Do not obscure the actual save result or print credential-bearing errors.
                }
            }
        }
    }
}
