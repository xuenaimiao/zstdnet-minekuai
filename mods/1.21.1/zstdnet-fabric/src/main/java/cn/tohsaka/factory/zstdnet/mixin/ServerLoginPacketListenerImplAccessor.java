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

package cn.tohsaka.factory.zstdnet.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@code ServerLoginPacketListenerImpl} 的登录档案与连接，用于「登录阶段正版验证」。
 * <p>
 * 1.20.2+（含 1.21.1）登录档案字段为 {@code authenticatedProfile}（1.20.1 为 {@code gameProfile}）。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccessor {

    @Accessor("authenticatedProfile")
    GameProfile zstdnet$getGameProfile();

    @Accessor("authenticatedProfile")
    void zstdnet$setGameProfile(GameProfile profile);

    @Accessor("connection")
    Connection zstdnet$getConnection();
}
