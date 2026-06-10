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
    private static final ForgeConfigSpec.BooleanValue LONG_DISTANCE_MATCHING;
    private static final ForgeConfigSpec.IntValue WINDOW_LOG;
    private static final ForgeConfigSpec.ConfigValue<String> DICTIONARY;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

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
}
