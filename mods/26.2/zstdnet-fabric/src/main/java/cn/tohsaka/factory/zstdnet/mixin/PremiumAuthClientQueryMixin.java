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

import cn.tohsaka.factory.zstdnet.network.PremiumAuthSync;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「登录阶段正版验证」客户端 mixin（MC 26.1）。识别我方 {@code zstdnet:auth/<hex>} 查询、本机
 * {@code joinServer} 后回一条空应答（见 {@link PremiumAuthSync#clientHandleQuery}）。优先级高于
 * fabric-networking-api，确保先于其客户端登录处理接管我方信道。
 */
@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 1500)
public abstract class PremiumAuthClientQueryMixin {

    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void zstdnet$premiumClientQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (PremiumAuthSync.clientHandleQuery(this.connection, packet)) {
            ci.cancel();
        }
    }
}
