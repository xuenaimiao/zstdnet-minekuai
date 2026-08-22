/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 记下握手包里那串<b>原始</b>主机名（真实 IP 转发的来源，原 {@code zstdnet_client_intention_raw_host}）。
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
@Mixin(ClientIntentionPacket.class)
public abstract class ClientIntentionPacketMixin {

    /*
     * 必须在解码<b>当场</b>拿：hostName 字段随后会被原版截断，代理塞在后面的转发信息就没了。
     * 反编译核对过 21.11 / 26.1 / 26.2：这个构造器里 readUtf(int) 只出现一次，
     * 所以 @Redirect 不写 ordinal 也不会歧义。
     *
     * 处理方法是 static：这条重定向发生在构造器里，那时 this 还没初始化完。
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readUtf(I)Ljava/lang/String;")
    )
    private static String zstdnet$rememberRawHandshakeHost(FriendlyByteBuf buffer, int maxLength) {
        return ServerRealIpHooks.rememberRawHandshakeHostString(buffer.readUtf(maxLength));
    }
}
