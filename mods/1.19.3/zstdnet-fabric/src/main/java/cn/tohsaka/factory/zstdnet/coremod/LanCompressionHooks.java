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

package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import net.minecraft.server.MinecraftServer;

public final class LanCompressionHooks {
    public static final int LAN_THRESHOLD = 1048576;

    private LanCompressionHooks() {
    }

    public static boolean shouldOverrideCompressionThreshold(MinecraftServer server) {
        // 仅当「开放到局域网」的存档显式开启 lan_compression（FRP/隧道场景，给 zstd 让路）时，
        // 才抬高阈值关掉原版 zlib；默认局域网走原版压缩，体验与不装 mod 一致。
        return server != null && !server.isDedicatedServer() && server.isPublished()
            && ServerProxyConfigFile.readLanCompression();
    }
}
