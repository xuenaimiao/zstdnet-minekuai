/*
 * Copyright (c) 2026 wish (original author, MIT — https://github.com/wish131400/zstdnet)
 * Copyright (c) 2026 xuenai · 麦块联机 / MineKuai (https://minekuai.com)
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is a derivative work of the MIT-licensed ZstdNet by wish. wish's
 * original portions remain under the MIT License (see the LICENSE file); that
 * upstream grant is preserved and not revoked.
 *
 * This project as a whole — and all modifications and additions by xuenai — is
 * licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0
 * International License (CC BY-NC-SA 4.0). You may share and adapt it for
 * NON-COMMERCIAL purposes only, must give appropriate credit and retain the
 * copyright notices above, and must distribute your contributions under this
 * same license (share-alike, source included).
 *
 * You should have received a copy of the license along with ZstdNet.
 * If not, see <https://creativecommons.org/licenses/by-nc-sa/4.0/>.
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
    private static volatile boolean rawFallback = true;
    private static volatile boolean initialized;

    // compress_lan / raw_fallback 由共享的 ClientCompressionConfig 之外单独解析（其 Parsed 记录不含
    // 这些项，且 mods/common 不改），首次写文件时把这段带注释的默认追加到共享模板末尾。
    private static final String EXTRA_CONFIG_BLOCK = """

        # Compress LAN/loopback/private-IP targets too (for FRP/tunnel). Default off: LAN uses a plain
        # direct connection, same as without the mod. Public servers always compress regardless.
        compress_lan=false

        # Probe the server before taking over: if it does not answer ZSTD (e.g. a SakuraFrp/OpenFrp
        # tunnel mapping a vanilla LAN port), fall back to a plain direct connection and show a chat
        # notice after joining. Default on. Set false to force ZSTD (join fails with an error when
        # the server lacks ZstdNet).
        raw_fallback=true
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

    /** 服务端不说 ZSTD（樱花等联机映射的原版端口）时是否回退原版直连。默认 true。 */
    public static boolean rawFallback() {
        if (!initialized) {
            init();
        }
        return rawFallback;
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
                Files.writeString(PATH, ClientCompressionConfig.defaultConfigBody() + EXTRA_CONFIG_BLOCK, StandardCharsets.UTF_8);
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
        // 缺省（旧配置文件没有该键）时默认 true：开箱即兼容樱花等联机映射。
        String rawFallbackRaw = properties.getProperty("raw_fallback");
        rawFallback = rawFallbackRaw == null || Boolean.parseBoolean(rawFallbackRaw.trim());
        return ClientCompressionConfig.parse(properties, PATH.getParent(), LOGGER);
    }
}
