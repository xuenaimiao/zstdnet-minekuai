/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.LanCompressionHooks;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LAN 模式下把压缩阈值抬高（原 {@code zstdnet_lan_compression_threshold.js}）。
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
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {

    /*
     * 原 JS 是把 getCompressionThreshold 的方法体整个换掉、无条件返回 resolveLanCompressionThreshold。
     * 这里用 HEAD + cancellable 达到同一效果：判定逻辑本来就全在 hook 里（非 LAN 时它返回原版的
     * VANILLA_THRESHOLD），所以无条件覆盖是等价的，且比 @Overwrite 更容易与别的 mod 共存。
     */
    @Inject(method = "getCompressionThreshold", at = @At("HEAD"), cancellable = true)
    private void zstdnet$overrideLanCompressionThreshold(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(LanCompressionHooks.resolveLanCompressionThreshold((MinecraftServer) (Object) this));
    }
}
