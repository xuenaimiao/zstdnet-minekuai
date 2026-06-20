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

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.compress.DictionaryFiles;
import cn.tohsaka.factory.zstdnet.core.transform.TransformFormat;
import cn.tohsaka.factory.zstdnet.core.transform.TransformOptions;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

/**
 * Client-side config used by the local publisher.
 */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ModConfigSpec.IntValue LEVEL;
    private static final ModConfigSpec.BooleanValue LONG_DISTANCE_MATCHING;
    private static final ModConfigSpec.IntValue WINDOW_LOG;
    private static final ModConfigSpec.ConfigValue<String> DICTIONARY;
    private static final ModConfigSpec.BooleanValue TRANSFORM;
    private static final ModConfigSpec.BooleanValue CHUNK_CACHE;
    private static final ModConfigSpec.BooleanValue CHUNK_CACHE_PERSIST;
    private static final ModConfigSpec.IntValue CHUNK_CACHE_PERSIST_MB;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        LEVEL = builder
            .comment("zstd compression level for client->server stream")
            .defineInRange("level", 3, 1, 22);
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
