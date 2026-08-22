/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.PremiumAuthClientHooks;

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
 * 登录阶段正版验证的客户端一半（原 {@code zstdnet_premium_auth_client}）。
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
@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 1500)
public abstract class PremiumAuthClientQueryMixin {

    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void zstdnet$handleClientQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (PremiumAuthClientHooks.handleClientQuery(this.connection, packet)) {
            ci.cancel();
        }
    }
}
