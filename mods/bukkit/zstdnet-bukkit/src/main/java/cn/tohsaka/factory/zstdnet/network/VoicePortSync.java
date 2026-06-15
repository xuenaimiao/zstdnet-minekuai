/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.network;

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.VoicePortPlan;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 插件端「语音端口下发」（Bukkit plugin messaging 版，对应 mod 变体的 {@code network/VoicePortSync}）。
 *
 * <p>插件端没有 mod 的网络通道 API，但 Forge/NeoForge/Fabric 客户端的 {@code VoicePortSync} 接收器本质上
 * 只是在监听信道 {@code zstdnet:voice_port_sync} 上的一条原版 {@code custom_payload}——只要字节一致、来自谁不限。
 * 因此本类用 Bukkit 的插件消息，在玩家进服时把 {@link ServerProxyBootstrap#currentVoicePortPlan() 当前语音端口计划}
 * 用与各 mod 加载器一致的线格式发给客户端，客户端据此在本机为这些端口开监听（隧道 / 桥接），从而零配置兼容各类语音 mod。</p>
 *
 * <p><b>线格式按服务器 MC 版本分两支</b>（客户端 mod 必与服务器同版本）：</p>
 * <ul>
 *   <li><b>MC &lt; 1.20.2（1.19.2 / 1.20.1，旧网络）</b>：{@code [frameTag=0x00][version=0x01][utf transport][varint count][varint ports...]}。
 *       其中前导 {@code 0x00} 对齐 Forge/NeoForge {@code SimpleChannel} 的消息序号（本通道只有一条消息，序号恒为 0），
 *       Fabric 1.20.1 也已对齐读/写同一个前导字节，故 <b>同一份字节</b>可被这三类客户端解码。</li>
 *   <li><b>MC ≥ 1.20.2（1.21.x，原版 payload）</b>：{@code [utf transport][varint count][varint ports...]}，无前缀
 *       （信道 id 即判别符；NeoForge 1.21.1 与 Fabric 1.21.1 的 {@code StreamCodec} 完全一致）。</li>
 * </ul>
 *
 * <p>非 ZstdNet 客户端（原版 / 未装本 mod）收到未注册信道上的 custom_payload 会直接忽略，无副作用。
 * 早于 1.13 的服务器不支持带命名空间的插件信道，此时整功能关闭（代理仍照常工作）。</p>
 */
public final class VoicePortSync implements Listener {

    /** 与各 mod 变体一致的信道名（{@code Zstdnet.MODID} = {@code "zstdnet"}）。 */
    private static final String CHANNEL = "zstdnet:voice_port_sync";
    /** 旧网络前导帧标记，固定 0x00，对齐 Forge SimpleChannel 的消息序号。 */
    private static final byte FRAME_TAG = 0;
    /** 旧网络线格式版本字节，与 mod 端 {@code WIRE_VERSION} 一致。 */
    private static final byte WIRE_VERSION = 1;
    private static final int MAX_PORTS = 256;

    private static boolean initialized;
    private static JavaPlugin plugin;
    /** true=旧网络（带 frameTag+version 前缀，MC&lt;1.20.2）；false=原版 payload（无前缀，MC≥1.20.2）。 */
    private static boolean legacyWire;

    private VoicePortSync() {
    }

    /**
     * 插件 onEnable 时调用：按服务器版本确定线格式，注册出站信道与进服监听器。
     * 服务器版本过旧（不支持命名空间信道）则跳过，不影响代理本体。
     */
    public static void init(JavaPlugin pl) {
        if (initialized) {
            return;
        }
        int[] ver = parseServerVersion();
        if (ver == null || ver[1] < 13) {
            pl.getLogger().info("[zstdnet] voice port sync disabled: server MC version "
                + Bukkit.getBukkitVersion() + " predates namespaced plugin channels (need 1.13+).");
            return;
        }
        initialized = true;
        plugin = pl;
        legacyWire = isLegacyNetwork(ver);
        pl.getServer().getMessenger().registerOutgoingPluginChannel(pl, CHANNEL);
        pl.getServer().getPluginManager().registerEvents(new VoicePortSync(), pl);
        pl.getLogger().info("[zstdnet] voice port sync ready (wire=" + (legacyWire ? "legacy" : "payload")
            + "); ZstdNet mod clients will auto-arm detected voice ports.");
    }

    /** 玩家进服后，把当前语音端口计划下发给客户端（无独立语音端口时不发）。 */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        VoicePortPlan plan = ServerProxyBootstrap.currentVoicePortPlan();
        if (plan == null || plan.ports().isEmpty()) {
            return;
        }
        try {
            event.getPlayer().sendPluginMessage(plugin, CHANNEL, encode(plan));
        } catch (RuntimeException e) {
            // 玩家已掉线 / 信道未注册等：忽略，绝不影响进服流程。
            plugin.getLogger().fine("[zstdnet] voice port push to " + event.getPlayer().getName() + " skipped: " + e);
        }
    }

    /** 按当前线格式把端口计划编码为 custom_payload 负载字节。 */
    private static byte[] encode(VoicePortPlan plan) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (legacyWire) {
            out.write(FRAME_TAG & 0xFF);
            out.write(WIRE_VERSION & 0xFF);
        }
        writeUtf(out, plan.transport());
        List<Integer> ports = plan.ports();
        int count = Math.min(ports.size(), MAX_PORTS);
        out.writeBytes(VarIntCodec.encode(count));
        for (int i = 0; i < count; i++) {
            out.writeBytes(VarIntCodec.encode(ports.get(i)));
        }
        return out.toByteArray();
    }

    /** MC 的 UTF 编码：VarInt 字节长度 + UTF-8 字节（与 {@code FriendlyByteBuf.writeUtf} / {@code STRING_UTF8} 一致）。 */
    private static void writeUtf(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(VarIntCodec.encode(bytes.length));
        out.writeBytes(bytes);
    }

    private static boolean isLegacyNetwork(int[] ver) {
        int minor = ver[1];
        int patch = ver[2];
        if (minor < 20) {
            return true;          // 1.19.x 及更早：旧网络
        }
        if (minor > 20) {
            return false;         // 1.21+：原版 payload
        }
        return patch <= 1;        // 1.20 / 1.20.1 旧网络；1.20.2+ 原版 payload
    }

    /** 解析 {@code Bukkit.getBukkitVersion()}（如 {@code "1.20.1-R0.1-SNAPSHOT"}）→ {major, minor, patch}；失败返回 null。 */
    private static int[] parseServerVersion() {
        try {
            String v = Bukkit.getBukkitVersion().split("-")[0];
            String[] parts = v.split("\\.");
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
