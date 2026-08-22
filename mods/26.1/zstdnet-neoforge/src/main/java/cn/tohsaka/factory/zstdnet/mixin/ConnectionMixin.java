/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;

import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * {@code getRemoteAddress()} 返回转发后的真实地址（原 {@code zstdnet_connection_real_ip}）。
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
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    /*
     * 原 JS 换掉整个方法体、把 address 字段当 fallback 传进去。这里在 RETURN 上取原返回值当
     * fallback，效果相同而且不必 @Shadow 那个字段（字段名跨版本比方法名更容易变）。
     */
    @Inject(method = "getRemoteAddress", at = @At("RETURN"), cancellable = true)
    private void zstdnet$getForwardedRemoteAddress(CallbackInfoReturnable<SocketAddress> cir) {
        cir.setReturnValue(ServerRealIpHooks.getRemoteAddress((Connection) (Object) this, cir.getReturnValue()));
    }
}
