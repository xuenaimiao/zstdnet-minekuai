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

import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版 {@code PlayerTabOverlay#render} 仅在 {@code minecraft.isLocalServer() || connection.isEncrypted()}
 * 为真时才绘制玩家名左侧的头像（1.19.1 安全聊天更新引入的「连接安全」门控）。ZstdNet 为压缩明文而刻意不开 AES，
 * 故 {@code isEncrypted()} 恒为 false，正版玩家的 TAB 头像被原版隐藏（世界内皮肤仍正常）。此 mixin 把那处
 * {@code isEncrypted()} 调用重定向：对走 ZstdNet 本地代理（环回端点）的连接一并视作安全，恢复头像。
 * 仅影响本地代理连接，直连/非环回连接保持原版行为。
 */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {

    // 26.1 把渲染拆成「抽取渲染状态 + 渲染」两步：那处 isEncrypted 门控搬到了 extractRenderState（不再叫 render）。
    @Redirect(
        method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;isEncrypted()Z")
    )
    private boolean zstdnet$forceTabHeadOnProxy(Connection connection) {
        if (connection == null) {
            return false;
        }
        if (connection.isEncrypted()) {
            return true;
        }
        return LocalZstdNet.isLocalProxyEndpoint(connection.getRemoteAddress());
    }
}
