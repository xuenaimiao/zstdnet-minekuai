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
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「登录阶段正版验证」服务端 mixin（MC 26.1）。把正版换档提前到 fabric-api 触发
 * {@code ServerLoginConnectionEvents.QUERY_START} 之前，避免与 LuckPerms 等在 {@code QUERY_START}
 * 同步读 UUID 的权限系统冲突（见 {@link PremiumAuthSync}）。
 * <p>
 * 优先级高于 fabric-networking-api（默认 1000）的服务端登录 mixin，确保应答拦截先于其
 * {@code onQueryResponse} 运行。{@code tick()} 门控注入在方法首部，位置早于 fabric-api 对
 * {@code tickVerify} 的 {@code @Redirect}，与优先级无关。
 */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 1500)
public abstract class PremiumAuthServerLoginMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void zstdnet$premiumGate(CallbackInfo ci) {
        if (!PremiumAuthSync.gateProceed((ServerLoginPacketListenerImpl) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void zstdnet$premiumAnswer(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (PremiumAuthSync.serverHandleAnswer((ServerLoginPacketListenerImpl) (Object) this,
            packet.transactionId())) {
            ci.cancel();
        }
    }
}
