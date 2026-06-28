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

import cn.tohsaka.factory.zstdnet.core.transform.TransformFormat;
import cn.tohsaka.factory.zstdnet.core.transform.TransformOptions;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;
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

    /** 跨会话持久缓存的默认预算（MiB）。0/负 → 由 {@code LocalZstdNet} 用内置默认（64 MiB）。 */
    public static final int DEFAULT_CACHE_PERSIST_MB = 64;

    /**
     * 解析结果：客户端 level、附加压缩参数、实体包流变换偏好，以及区块引用缓存（CRC）偏好。
     *
     * @param cacheEnabled      是否启用 CRC（advertise + 安装逆向包装；仅服务端也启用时才真正生效）
     * @param cachePersist      是否跨会话落盘持久化（关掉则仅会话内 REF）
     * @param cachePersistBytes 持久缓存字节预算（{@code <=0} 表示用内置默认）
     */
    public static final class Parsed {
        private final int level;
        private final CompressionOptions compression;
        private final TransformOptions transform;
        private final boolean cacheEnabled;
        private final boolean cachePersist;
        private final long cachePersistBytes;

        public Parsed(int level, CompressionOptions compression, TransformOptions transform,
                      boolean cacheEnabled, boolean cachePersist, long cachePersistBytes) {
            this.level = level;
            this.compression = compression;
            this.transform = transform;
            this.cacheEnabled = cacheEnabled;
            this.cachePersist = cachePersist;
            this.cachePersistBytes = cachePersistBytes;
        }

        public int level() {
            return this.level;
        }

        public CompressionOptions compression() {
            return this.compression;
        }

        public TransformOptions transform() {
            return this.transform;
        }

        public boolean cacheEnabled() {
            return this.cacheEnabled;
        }

        public boolean cachePersist() {
            return this.cachePersist;
        }

        public long cachePersistBytes() {
            return this.cachePersistBytes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Parsed)) {
                return false;
            }
            Parsed other = (Parsed) o;
            return this.level == other.level
                && this.cacheEnabled == other.cacheEnabled
                && this.cachePersist == other.cachePersist
                && this.cachePersistBytes == other.cachePersistBytes
                && Objects.equals(this.compression, other.compression)
                && Objects.equals(this.transform, other.transform);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level, compression, transform, cacheEnabled, cachePersist, cachePersistBytes);
        }

        @Override
        public String toString() {
            return "Parsed[level=" + level + ", compression=" + compression + ", transform=" + transform
                + ", cacheEnabled=" + cacheEnabled + ", cachePersist=" + cachePersist
                + ", cachePersistBytes=" + cachePersistBytes + "]";
        }
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

        boolean transformOn = Boolean.parseBoolean(trimmed(props, "transform", "false"));
        TransformOptions transform = transformOn
            ? TransformOptions.enabled(TransformFormat.MAX_SUPPORTED_VERSION, 0)
            : TransformOptions.disabled();

        boolean cacheEnabled = Boolean.parseBoolean(trimmed(props, "chunk_cache", "true"));
        boolean cachePersist = Boolean.parseBoolean(trimmed(props, "chunk_cache_persist", "true"));
        int persistMb = parseInt(props.getProperty("chunk_cache_persist_mb"), DEFAULT_CACHE_PERSIST_MB);
        long cachePersistBytes = persistMb > 0 ? (long) persistMb * 1024 * 1024 : 0L;
        return new Parsed(level, compression, transform, cacheEnabled, cachePersist, cachePersistBytes);
    }

    /** 首次生成 {@code zstdnet-client.toml} 时写入的带注释模板。 */
    public static String defaultConfigBody() {
        return String.format(
            "# ZstdNet client config\n"
            + "# zstd compression level for the local client proxy (1-22)\n"
            + "level=%d\n"
            + "\n"
            + "# Long-distance matching (LDM): better ratio for highly repetitive servers,\n"
            + "# at the cost of extra per-connection memory. Default off.\n"
            + "# Only enable window_log > 27 if the target server also uses it.\n"
            + "long_distance_matching=false\n"
            + "# LDM window as a power-of-two exponent. 0 = conservative default (24, ~16MiB).\n"
            + "window_log=0\n"
            + "\n"
            + "# Trained dictionary file name under config/zstdnet/dict/ (or an absolute path).\n"
            + "# Leave empty to disable. Only set this when the SERVER you connect to ships the\n"
            + "# SAME dictionary file (ask the server admin); otherwise the connection will fail.\n"
            + "dictionary=\n"
            + "\n"
            + "# Entity packet-stream transform: de-interleaves entity move/metadata fields before zstd\n"
            + "# for much better ratio in entity-heavy scenes. Only takes effect if the SERVER also enables\n"
            + "# it; safe and byte-identical against servers that don't (auto passthrough). Default off.\n"
            + "transform=false\n"
            + "\n"
            + "# Chunk reference cache (CRC): de-duplicates repeated chunk data before zstd (8-byte REF tokens\n"
            + "# for chunks you already hold). Only takes effect if the SERVER also enables it (chunk_cache=\n"
            + "# auto/ref/full); safe and byte-identical against servers that don't. Default on.\n"
            + "chunk_cache=true\n"
            + "# Persist full chunks to disk (config/zstdnet-cache/<server>/) so reconnecting can replay\n"
            + "# already-held chunks (WARM_REF) across sessions. Off = in-session de-dup only. Default on.\n"
            + "chunk_cache_persist=true\n"
            + "# Disk+memory budget for the cross-session cache, in MiB (per server). Default %d.\n"
            + "chunk_cache_persist_mb=%d\n",
            DEFAULT_LEVEL, DEFAULT_CACHE_PERSIST_MB, DEFAULT_CACHE_PERSIST_MB);
    }

    private static String trimmed(Properties props, String key, String fallback) {
        String raw = props.getProperty(key);
        return raw == null || raw.trim().isEmpty() ? fallback : raw.trim();
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
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
