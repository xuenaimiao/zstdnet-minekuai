/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.PremiumAuthServerHooks;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录阶段正版验证的服务端两半（原 {@code zstdnet_premium_auth_server}）。
 *
 * <h2>为什么是 Mixin 而不是 coremod</h2>
 * 本变体原来用 JS coremod（{@code META-INF/coremods.json} + {@code coremods/*.js}）做注入。
 * NeoForge 已经把整套 JS coremod 删掉了：{@code net.neoforged:coremods}（含 {@code ASMAPI}）
 * 最后一次出现在 NeoForge <b>21.1.x</b> 的依赖里，21.11 / 26.1 / 26.2 都不再带它，
 * {@code coremods.json} 于是被<b>静默忽略</b> —— mod 照常加载、日志里一个字都没有，
 * 只有这些注入全部没装上。实测症状就是
 * {@code [zstdnet-server] auto_takeover=true but no dedicated auto-port plan was prepared}。
 * <p>
 * 注入点、调用的静态方法、传参与语义都与原 JS 逐条对应，行为不变。
 */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 1500)
public abstract class PremiumAuthServerLoginMixin {

    /*
     * 门控收尾：hook 返回 false 就直接 return，把登录停在这一步等我方验证结果。
     *
     * 原 JS 不敢按名字找这个方法（它与 startClientVerification / finishLoginAndWaitForClient
     * 描述符相同），改成「(GameProfile)V 里唯一 new ClientboundLoginCompressionPacket 的那个」。
     * 走 Mixin 就不需要这套了：NeoForge 1.20.2+ 运行期用的就是 Mojang 官方名，按名字匹配即可。
     * 反编译核对过 21.11 / 26.1 / 26.2，三版都叫 verifyLoginAndFinishConnectionSetup。
     */
    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
    private void zstdnet$gateFinalizeLogin(GameProfile profile, CallbackInfo ci) {
        if (!PremiumAuthServerHooks.beforeFinalizeLogin((ServerLoginPacketListenerImpl) (Object) this)) {
            ci.cancel();
        }
    }

    /* 我方的自定义查询回包由 hook 吃掉（返回 true）；不是我方的原样交还原版。 */
    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void zstdnet$handleAnswer(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (PremiumAuthServerHooks.handleAnswer((ServerLoginPacketListenerImpl) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
