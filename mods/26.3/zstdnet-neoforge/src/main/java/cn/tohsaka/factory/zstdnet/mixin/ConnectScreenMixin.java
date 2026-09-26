/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ConnectScreenHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 把要连的地址换成本地代理入口（原 {@code zstdnet_connect_screen_intercept}）。
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
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {

    /*
     * 原 JS 是在方法开头 ALOAD 2 / ALOAD 3 → interceptConnect → ASTORE 2，也就是「改掉
     * serverAddress 这个形参」。argsOnly = true 的 @ModifyVariable 表达的正是这件事。
     * 形参表要写全，因为 hook 还需要 serverData 来判断这次连接该不该走代理。
     */
    @ModifyVariable(
            method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;"
                    + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
                    + "Lnet/minecraft/client/multiplayer/ServerData;Z"
                    + "Lnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private static ServerAddress zstdnet$interceptConnect(
            ServerAddress original,
            Screen parent,
            Minecraft minecraft,
            ServerAddress serverAddress,
            ServerData serverData,
            boolean quickPlay,
            TransferState transferState
    ) {
        return ConnectScreenHooks.interceptConnect(original, serverData);
    }
}
