/*
 * Copyright (c) 2026 tohsaka · ZstdNet
 */

package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.server.DedicatedServerAutoPort;

import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 自动端口接管：改写 {@code initServer} 里读出来的那份服务器属性（原 {@code zstdnet_dedicated_auto_port}）。
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
@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {

    /*
     * 原 JS 的定位方式是「第一处 getProperties() 调用、且下一条指令就是 ASTORE」。
     * 反编译核对过 21.11 / 26.1 / 26.2 的 initServer：整个方法里符合这个形状的<b>只有一处</b>
     * （21.11 与 26.1 在偏移 289→astore_3，26.2 在 101→astore_2），其余 getProperties() 后面
     * 跟的都是 getfield，取的是某个字段而不是这份属性本身。
     *
     * @ModifyVariable + @At("STORE") 表达的正是同一件事，而且更稳：它按<b>局部变量类型</b>
     * 选第一次写入，不依赖「下一条指令恰好是 ASTORE」这种字节码排布上的巧合。
     */
    @ModifyVariable(method = "initServer", at = @At("STORE"), ordinal = 0)
    private DedicatedServerProperties zstdnet$prepareDedicatedServerProperties(DedicatedServerProperties current) {
        return DedicatedServerAutoPort.prepareDedicatedServerProperties((DedicatedServer) (Object) this, current);
    }
}
