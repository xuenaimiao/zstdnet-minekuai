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

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.compress.DictionaryFiles;
import cn.tohsaka.factory.zstdnet.core.transform.TransformFormat;
import cn.tohsaka.factory.zstdnet.core.transform.TransformOptions;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;

/**
 * Client-side config used by the local publisher.
 */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ForgeConfigSpec.IntValue LEVEL;
    private static final ForgeConfigSpec.BooleanValue COMPRESS_LAN;
    private static final ForgeConfigSpec.BooleanValue RAW_FALLBACK;
    private static final ForgeConfigSpec.BooleanValue LONG_DISTANCE_MATCHING;
    private static final ForgeConfigSpec.IntValue WINDOW_LOG;
    private static final ForgeConfigSpec.ConfigValue<String> DICTIONARY;
    private static final ForgeConfigSpec.BooleanValue TRANSFORM;
    private static final ForgeConfigSpec.BooleanValue CHUNK_CACHE;
    private static final ForgeConfigSpec.BooleanValue CHUNK_CACHE_PERSIST;
    private static final ForgeConfigSpec.IntValue CHUNK_CACHE_PERSIST_MB;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        LEVEL = builder
            .comment("zstd compression level for client->server stream")
            .defineInRange("level", 3, 1, 22);
        COMPRESS_LAN = builder
            .comment("Compress LAN/loopback/private-IP targets too (for FRP/tunnel). Default off: LAN uses a plain direct connection, same as without the mod. Public servers always compress regardless.")
            .define("compress_lan", false);
        RAW_FALLBACK = builder
            .comment("Probe the server before taking over: if it does not answer ZSTD (e.g. a SakuraFrp/OpenFrp tunnel mapping a vanilla LAN port), fall back to a plain direct connection and show a chat notice after joining. Default on. Set false to force ZSTD (join fails with an error when the server lacks ZstdNet).")
            .define("raw_fallback", true);
        LONG_DISTANCE_MATCHING = builder
            .comment("Enable long-distance matching (better ratio on repetitive servers, more memory). Default off.")
            .define("long_distance_matching", false);
        WINDOW_LOG = builder
            .comment("LDM window as a power-of-two exponent. 0 = conservative default (24). Only >27 requires the server to use it too.")
            .defineInRange("window_log", 0, 0, 31);
        DICTIONARY = builder
            .comment("Trained dictionary file under config/zstdnet/dict/ (or absolute path). Empty = off. Must match the server's dictionary.")
            .define("dictionary", "");
        TRANSFORM = builder
            .comment("Entity packet-stream transform: better ratio in entity-heavy scenes. Only active if the server enables it too; byte-identical fallback otherwise. Default off.")
            .define("transform", false);
        CHUNK_CACHE = builder
            .comment("Chunk reference cache: de-duplicates repeated chunk data before zstd. Only active if the server enables it too (chunk_cache=auto/ref/full); byte-identical fallback otherwise. Default on.")
            .define("chunk_cache", true);
        CHUNK_CACHE_PERSIST = builder
            .comment("Persist full chunks to disk so reconnecting can replay already-held chunks (WARM_REF) across sessions. Off = in-session de-dup only. Default on.")
            .define("chunk_cache_persist", true);
        CHUNK_CACHE_PERSIST_MB = builder
            .comment("Disk+memory budget for the cross-session chunk cache, in MiB (per server). Default 64.")
            .defineInRange("chunk_cache_persist_mb", 64, 1, 4096);

        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    public static int getLevel() {
        return LEVEL.get();
    }

    /** 是否对局域网/本机/私网目标也启用压缩。默认 false：局域网走原版直连。 */
    public static boolean compressLan() {
        return COMPRESS_LAN.get();
    }

    /** 服务端不说 ZSTD（樱花等联机映射的原版端口）时是否回退原版直连。默认 true。 */
    public static boolean rawFallback() {
        return RAW_FALLBACK.get();
    }

    public static CompressionOptions compression() {
        boolean ldm = LONG_DISTANCE_MATCHING.get();
        int windowLog = WINDOW_LOG.get();
        String dictName = DICTIONARY.get();
        byte[] dictionary = null;
        if (dictName != null && !dictName.isBlank()) {
            try {
                dictionary = DictionaryFiles.load(Platforms.get().configDir(), dictName);
            } catch (Exception ex) {
                LOGGER.error("[zstdnet-client] failed to load dictionary '{}'; continuing without it: {}", dictName, ex.toString());
            }
        }
        return CompressionOptions.of(ldm, windowLog, dictionary);
    }

    public static TransformOptions transform() {
        return TRANSFORM.get()
            ? TransformOptions.enabled(TransformFormat.MAX_SUPPORTED_VERSION, 0)
            : TransformOptions.disabled();
    }

    public static boolean cacheEnabled() {
        return CHUNK_CACHE.get();
    }

    public static boolean cachePersist() {
        return CHUNK_CACHE_PERSIST.get();
    }

    public static long cachePersistBytes() {
        return (long) CHUNK_CACHE_PERSIST_MB.get() * 1024 * 1024;
    }
}
