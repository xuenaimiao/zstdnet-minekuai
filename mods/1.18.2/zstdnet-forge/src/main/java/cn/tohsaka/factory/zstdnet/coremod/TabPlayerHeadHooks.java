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

import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import net.minecraft.network.Connection;

/**
 * 「TAB 列表玩家头像」客户端 coremod 钩子。
 * <p>
 * 原版 {@code PlayerTabOverlay#render} 仅在 {@code minecraft.isLocalServer() || connection.isEncrypted()}
 * 为真时才绘制玩家名左侧的头像（「连接是否安全」门控）。ZstdNet 为了让 ZSTD 压缩明文流量而<b>刻意不做 AES
 * 加密握手</b>（内置正版验证也走明文带外校验），于是 {@code isEncrypted()} 恒为 {@code false} —— 正版玩家的
 * 皮肤在世界里正常显示，但 TAB 列表却因「连接未加密」被原版判为不安全而隐藏头像。
 * <p>
 * 由 {@code coremods/zstdnet_tab_player_head.js} 把 {@code render} 内唯一的 {@code Connection.isEncrypted()}
 * 调用替换为本方法：对走 ZstdNet 本地代理的连接（环回端点）一并视作安全连接，从而恢复头像绘制。
 * 仅影响走本地代理的连接；直连/非环回连接保持原版行为。
 */
public final class TabPlayerHeadHooks {

    private TabPlayerHeadHooks() {
    }

    /**
     * 替换 {@code PlayerTabOverlay#render} 中的 {@code connection.isEncrypted()}。
     * 连接真已加密，或它是当前活跃的 ZstdNet 本地代理端点时，返回 {@code true}（绘制头像）。
     */
    public static boolean isEncryptedOrZstdProxied(Connection connection) {
        if (connection == null) {
            return false;
        }
        if (connection.isEncrypted()) {
            return true;
        }
        return LocalZstdNet.isLocalProxyEndpoint(connection.getRemoteAddress());
    }
}
