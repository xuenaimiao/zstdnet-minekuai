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

package cn.tohsaka.factory.zstdnet.platform;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge 平台实现（1.16.5）。
 */
public final class ForgePlatform implements Platform {

    @Override
    public Path configDir() {
        return FMLPaths.GAMEDIR.get().resolve("config");
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public String adjustHandshakeHostSuffix(String hostSuffix) {
        if (hostSuffix != null && (hostSuffix.contains("\0FML2\0") || hostSuffix.contains("\0FML3\0"))) {
            return hostSuffix;
        }
        return "\0FML2\0";
    }

    @Override
    public boolean supportsPremiumVerification() {
        // 登录阶段正版验证依赖旧版登录流程的 coremod（ServerLoginNetHandler / ClientLoginNetHandler 等）。
        // 1.16.5 变体首轮暂不实现该挂钩，保持历史行为（绝不在无法验证时擅自把后端切离线）。
        return false;
    }
}
