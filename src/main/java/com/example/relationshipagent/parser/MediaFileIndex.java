package com.example.relationshipagent.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 媒体文件反查索引(M4):按消息时间戳前缀反查真实文件路径,替代按规则拼接的假路径。
 * <p>
 * 关键事实(已实证):image/voice 文件名前 15 字符(yyyyMMdd-HHmmss)与消息 CreateTime
 * (Asia/Shanghai)精确一致;video 文件名前 14 字符(yyyyMMddHHmmss)同理。
 * file/ 文件名无时间戳,一期不做关联(resolve 返回 empty)。
 * <p>
 * 目录布局:image 与 video 按月份子目录(image/2021-01/...),voice 为扁平(voice/...);
 * 故扫描时递归遍历,兼容两种布局。
 */
@Component
public class MediaFileIndex {

    private static final Logger log = LoggerFactory.getLogger(MediaFileIndex.class);

    /**
     * image/voice 文件名前缀格式:15 字符 yyyyMMdd-HHmmss
     */
    private static final DateTimeFormatter FMT_15 = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /**
     * video 文件名前缀格式:14 字符 yyyyMMddHHmmss
     */
    private static final DateTimeFormatter FMT_14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 未命中时的相邻秒搜索窗口(含)
     */
    private static final int WINDOW_SECONDS = 5;

    private final String rootDir;

    private volatile boolean scanned = false;
    private final Map<String, String> imageIndex = new HashMap<>();
    private final Map<String, String> voiceIndex = new HashMap<>();
    private final Map<String, String> videoIndex = new HashMap<>();

    public MediaFileIndex(@Value("${chat-history.root-dir:}") String rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * 按消息类型与时间反查真实文件相对路径。
     *
     * @param type        媒体类型(IMAGE/VOICE/VIDEO/FILE)
     * @param messageTime 消息时间(UTC Instant)
     * @param zoneId      源时区(以 chat_file.source_timezone 为准,默认 Asia/Shanghai)
     * @return 命中返回相对 root 的路径(如 image/2021-01/20210107-182249-103168.jpg);
     * FILE 或未命中返回 empty
     */
    public Optional<String> resolve(MessageType type, Instant messageTime, ZoneId zoneId) {
        if (type == MessageType.FILE) return Optional.empty();
        ensureScanned();

        Map<String, String> index;
        boolean fifteen;
        switch (type) {
            case IMAGE -> {
                index = imageIndex;
                fifteen = true;
            }
            case VOICE -> {
                index = voiceIndex;
                fifteen = true;
            }
            case VIDEO -> {
                index = videoIndex;
                fifteen = false;
            }
            default -> {
                return Optional.empty();
            }
        }

        // 精确匹配,未命中再尝试 ±5 秒窗口(处理导出工具命名与消息时间的秒级偏差)
        for (int offset = 0; offset <= WINDOW_SECONDS; offset++) {
            for (int sign : (offset == 0 ? new int[]{0} : new int[]{1, -1})) {
                Instant t = messageTime.plusSeconds((long) sign * offset);
                String key = formatKey(t, zoneId, fifteen);
                String rel = index.get(key);
                if (rel != null) return Optional.of(rel);
            }
        }
        return Optional.empty();
    }

    private String formatKey(Instant t, ZoneId zoneId, boolean fifteen) {
        return (fifteen ? FMT_15 : FMT_14).format(t.atZone(zoneId));
    }

    private void ensureScanned() {
        if (scanned) return;
        synchronized (this) {
            if (scanned) return;
            scan();
            scanned = true;
        }
    }

    private void scan() {
        if (rootDir == null || rootDir.isBlank()) {
            log.warn("chat-history.root-dir 未配置,媒体反查索引为空");
            return;
        }
        Path root = Paths.get(rootDir);
        scanDir(root, "image", imageIndex, 15);
        scanDir(root, "voice", voiceIndex, 15);
        scanDir(root, "video", videoIndex, 14);
        log.info("MediaFileIndex scanned: image={}, voice={}, video={}",
                imageIndex.size(), voiceIndex.size(), videoIndex.size());
    }

    private void scanDir(Path root, String sub, Map<String, String> index, int prefixLen) {
        Path dir = root.resolve(sub);
        if (!Files.isDirectory(dir)) {
            log.debug("Media dir not found, skip: {}", dir);
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.length() < prefixLen) return;
                String key = name.substring(0, prefixLen);
                String rel = relativize(root, file);
                // 同前缀多次出现时保留首个,避免随机后缀冲突时覆盖
                index.putIfAbsent(key, rel);
            });
        } catch (IOException e) {
            log.warn("Failed to scan media dir {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 相对 root 的路径,统一用正斜杠(跨平台一致、与设计文档示例一致)
     */
    private String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
