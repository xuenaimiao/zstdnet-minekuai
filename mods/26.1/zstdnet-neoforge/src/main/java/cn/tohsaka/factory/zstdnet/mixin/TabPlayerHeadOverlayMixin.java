/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.TabPlayerHeadHooks;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TAB 列表头像的「连接是否安全」门控（原 {@code zstdnet_tab_player_head}）。
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
@Mixin(PlayerTabOverlay.class)
abstract class TabPlayerHeadOverlayMixin {

    /*
     * 这一条<b>每个版本都不一样</b>，所以不能抄别的变体：
     *   1.21.11  PlayerTabOverlay#render             门控是 Connection#isEncrypted()
     *   26.1     PlayerTabOverlay#extractRenderState 门控是 Connection#isEncrypted()
     *   26.2     PlayerTabOverlay#extractRenderState 门控改成 ClientPacketListener#onlineMode()
     *            （Connection#isEncrypted 在 26.2 上已经被删掉了）
     * 以上三条都是反编译各自版本的 PlayerTabOverlay 逐一核对过的，且每版都只有一处调用。
     */
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;isEncrypted()Z"))
    private boolean zstdnet$forceTabHeadOnProxy(Connection gate) {
        return TabPlayerHeadHooks.isEncryptedOrZstdProxied(gate);
    }
}
