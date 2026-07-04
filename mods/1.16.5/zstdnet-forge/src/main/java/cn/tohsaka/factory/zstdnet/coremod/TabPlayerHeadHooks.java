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
