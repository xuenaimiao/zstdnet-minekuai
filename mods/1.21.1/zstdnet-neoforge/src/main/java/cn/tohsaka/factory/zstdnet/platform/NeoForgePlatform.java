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

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge 平台实现。
 * <p>
 * 握手后缀沿用默认（原样返回）——NeoForge 变体原行为不强制 FML 标记。
 */
public final class NeoForgePlatform implements Platform {

    @Override
    public Path configDir() {
        return FMLPaths.GAMEDIR.get().resolve("config");
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public boolean supportsPremiumVerification() {
        // 登录阶段正版验证已由 coremod/PremiumAuth{Server,Client}Hooks + coremods/zstdnet_premium_auth.js 实现。
        return true;
    }
}
