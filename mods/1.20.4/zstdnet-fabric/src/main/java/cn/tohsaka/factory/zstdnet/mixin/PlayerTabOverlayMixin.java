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

    @Redirect(
        method = "render",
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
