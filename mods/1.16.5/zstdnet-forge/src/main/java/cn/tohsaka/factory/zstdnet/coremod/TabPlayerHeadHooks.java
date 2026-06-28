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
import net.minecraft.network.NetworkManager;

/**
 * 「TAB 列表玩家头像」客户端 coremod 钩子（1.16.5）。
 * <p>
 * 原版 {@code PlayerTabOverlayGui#func_238523_a_}（render）仅在
 * {@code minecraft.isIntegratedServerRunning() || connection.isEncrypted()} 为真时才绘制玩家名左侧头像
 * （「连接是否安全」门控）。ZstdNet 为了让 ZSTD 压缩明文流量而<b>刻意不做 AES 加密握手</b>，于是
 * {@code isEncrypted()} 恒为 {@code false} —— 正版玩家的皮肤在世界里正常显示，但 TAB 列表却被原版判为
 * 不安全而隐藏头像。由 {@code coremods/zstdnet_tab_player_head.js} 把 render 内唯一的
 * {@code NetworkManager.isEncrypted()} 调用替换为本方法：对走 ZstdNet 本地代理的连接（环回端点）一并
 * 视作安全连接，从而恢复头像绘制。仅影响走本地代理的连接；直连/非环回连接保持原版行为。
 */
public final class TabPlayerHeadHooks {

    private TabPlayerHeadHooks() {
    }

    /**
     * 替换 render 中的 {@code connection.isEncrypted()}。连接真已加密，或它是当前活跃的 ZstdNet 本地代理
     * 端点时，返回 {@code true}（绘制头像）。
     */
    public static boolean isEncryptedOrZstdProxied(NetworkManager connection) {
        if (connection == null) {
            return false;
        }
        if (connection.isEncrypted()) {
            return true;
        }
        return LocalZstdNet.isLocalProxyEndpoint(connection.getRemoteAddress());
    }
}
