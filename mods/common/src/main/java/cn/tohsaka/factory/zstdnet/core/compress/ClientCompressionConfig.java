/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.core.compress;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Properties;

/**
 * 客户端压缩配置的<b>共享</b>解析逻辑（level + 可选 LDM + 可选字典）。
 * <p>
 * 各 loader 的 {@code ClientConfig} 只做“定位 config 目录 + 读文件”的薄壳，把解析与默认模板
 * 统一委托到这里，避免 4 份变体各写一遍导致逻辑漂移。所有附加项默认关闭，{@link #parse} 在
 * 缺省配置下返回 {@link CompressionOptions#none()}，压缩行为与历史一致。
 */
public final class ClientCompressionConfig {
    public static final int DEFAULT_LEVEL = 3;

    private ClientCompressionConfig() {
    }

    /** 解析结果：客户端 level 与附加压缩参数。 */
    public record Parsed(int level, CompressionOptions compression) {
    }

    /**
     * @param props     已从 {@code zstdnet-client.toml} 读入的键值（用 {@link Properties} 读 key=value）
     * @param configDir 客户端 config 目录，用于解析 {@code dictionary} 文件
     * @param log       记日志用（可为 null）
     */
    public static Parsed parse(Properties props, Path configDir, Logger log) {
        int level = clamp(parseInt(props.getProperty("level"), DEFAULT_LEVEL), 1, 22);
        boolean ldm = Boolean.parseBoolean(trimmed(props, "long_distance_matching", "false"));
        int windowLog = parseInt(props.getProperty("window_log"), 0);

        byte[] dictionary = null;
        String dictName = trimmed(props, "dictionary", "");
        if (!dictName.isEmpty()) {
            try {
                dictionary = DictionaryFiles.load(configDir, dictName);
            } catch (Exception ex) {
                if (log != null) {
                    log.error("[zstdnet-client] failed to load dictionary '{}'; continuing without it: {}", dictName, ex.toString());
                }
                dictionary = null;
            }
        }

        CompressionOptions compression = CompressionOptions.of(ldm, windowLog, dictionary);
        if (log != null && compression.hasDictionary()) {
            log.info("[zstdnet-client] loaded dictionary '{}' ({} bytes, id {})", dictName, dictionary.length, compression.dictionaryId());
        }
        return new Parsed(level, compression);
    }

    /** 首次生成 {@code zstdnet-client.toml} 时写入的带注释模板。 */
    public static String defaultConfigBody() {
        return """
            # ZstdNet client config
            # zstd compression level for the local client proxy (1-22)
            level=%d

            # Long-distance matching (LDM): better ratio for highly repetitive servers,
            # at the cost of extra per-connection memory. Default off.
            # Only enable window_log > 27 if the target server also uses it.
            long_distance_matching=false
            # LDM window as a power-of-two exponent. 0 = conservative default (24, ~16MiB).
            window_log=0

            # Trained dictionary file name under config/zstdnet/dict/ (or an absolute path).
            # Leave empty to disable. Only set this when the SERVER you connect to ships the
            # SAME dictionary file (ask the server admin); otherwise the connection will fail.
            dictionary=
            """.formatted(DEFAULT_LEVEL);
    }

    private static String trimmed(Properties props, String key, String fallback) {
        String raw = props.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
