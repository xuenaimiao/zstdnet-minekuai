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

package cn.tohsaka.factory.zstdnet;

import cn.tohsaka.factory.zstdnet.core.compress.ClientCompressionConfig;
import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.transform.TransformOptions;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side config used by the local publisher.
 * <p>
 * 解析逻辑统一委托到共享的 {@link ClientCompressionConfig}，本类只负责定位 config 目录与读写文件。
 */
public final class ClientConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("zstdnet-client.toml");

    private static volatile int level = ClientCompressionConfig.DEFAULT_LEVEL;
    private static volatile CompressionOptions compression = CompressionOptions.none();
    private static volatile TransformOptions transform = TransformOptions.disabled();
    private static volatile boolean cacheEnabled = true;
    private static volatile boolean cachePersist = true;
    private static volatile long cachePersistBytes = 0L;
    private static volatile boolean compressLan = false;
    private static volatile boolean initialized;

    // compress_lan 由共享的 ClientCompressionConfig 之外单独解析（其 Parsed 记录不含该项，
    // 且 mods/common 不改），首次写文件时把这段带注释的默认追加到共享模板末尾。
    private static final String COMPRESS_LAN_CONFIG_BLOCK = """

        # Compress LAN/loopback/private-IP targets too (for FRP/tunnel). Default off: LAN uses a plain
        # direct connection, same as without the mod. Public servers always compress regardless.
        compress_lan=false
        """;

    private ClientConfig() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        synchronized (ClientConfig.class) {
            if (initialized) {
                return;
            }
            ClientCompressionConfig.Parsed parsed = loadOrCreate();
            level = parsed.level();
            compression = parsed.compression();
            transform = parsed.transform();
            cacheEnabled = parsed.cacheEnabled();
            cachePersist = parsed.cachePersist();
            cachePersistBytes = parsed.cachePersistBytes();
            initialized = true;
        }
    }

    public static int getLevel() {
        if (!initialized) {
            init();
        }
        return level;
    }

    /** 是否对局域网/本机/私网目标也启用压缩。默认 false：局域网走原版直连。 */
    public static boolean compressLan() {
        if (!initialized) {
            init();
        }
        return compressLan;
    }

    public static CompressionOptions compression() {
        if (!initialized) {
            init();
        }
        return compression;
    }

    public static TransformOptions transform() {
        if (!initialized) {
            init();
        }
        return transform;
    }

    public static boolean cacheEnabled() {
        if (!initialized) {
            init();
        }
        return cacheEnabled;
    }

    public static boolean cachePersist() {
        if (!initialized) {
            init();
        }
        return cachePersist;
    }

    public static long cachePersistBytes() {
        if (!initialized) {
            init();
        }
        return cachePersistBytes;
    }

    private static ClientCompressionConfig.Parsed loadOrCreate() {
        ClientCompressionConfig.Parsed fallback = new ClientCompressionConfig.Parsed(ClientCompressionConfig.DEFAULT_LEVEL, CompressionOptions.none(), TransformOptions.disabled(), true, true, 0L);
        try {
            Files.createDirectories(PATH.getParent());
            if (!Files.exists(PATH)) {
                Files.writeString(PATH, ClientCompressionConfig.defaultConfigBody() + COMPRESS_LAN_CONFIG_BLOCK, StandardCharsets.UTF_8);
                return fallback;
            }
        } catch (IOException ignored) {
            return fallback;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(PATH)) {
            properties.load(input);
        } catch (IOException ignored) {
            return fallback;
        }
        String compressLanRaw = properties.getProperty("compress_lan");
        compressLan = compressLanRaw != null && Boolean.parseBoolean(compressLanRaw.trim());
        return ClientCompressionConfig.parse(properties, PATH.getParent(), LOGGER);
    }
}
