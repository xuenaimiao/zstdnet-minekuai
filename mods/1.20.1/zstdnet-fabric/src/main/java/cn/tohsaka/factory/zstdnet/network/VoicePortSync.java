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

import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.VoicePortPlan;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * play 阶段「语音端口下发」（MC 1.20.1 Fabric 网络 API）。
 *
 * <p>服务端探测到后端语音 mod 的独立端口后，玩家进服时把「传输方式 + 端口列表」下发给客户端；
 * 客户端据此在本机为这些端口开监听（tunnel 打标隧道 / bridge 直连），从而零配置兼容各类语音 mod。
 * 列表顺序即隧道 channelId，两端必须一致。旧服务端不发本消息、旧客户端不认本消息时，行为完全同历史版本。</p>
 */
public final class VoicePortSync {
    private static final ResourceLocation VOICE_PORT_SYNC_ID = new ResourceLocation(Zstdnet.MODID, "voice_port_sync");
    private static final byte WIRE_VERSION = 1;
    private static final int MAX_PORTS = 256;

    private static boolean clientInitialized;

    private VoicePortSync() {
    }

    public static void initClient() {
        if (clientInitialized || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        clientInitialized = true;
        ClientPlayNetworking.registerGlobalReceiver(VOICE_PORT_SYNC_ID, (client, handler, buf, responseSender) -> {
            buf.readByte(); // wire version (reserved)
            String transport = buf.readUtf(32);
            int count = Math.min(buf.readVarInt(), MAX_PORTS);
            List<Integer> ports = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                ports.add(buf.readVarInt());
            }
            client.execute(() -> ClientProxyPublisher.acceptVoicePortList(transport, ports));
        });
    }

    /** 服务端：玩家进服后下发当前语音端口计划（空列表也下发，让客户端撤销旧监听）。 */
    public static void send(ServerPlayer player) {
        VoicePortPlan plan = ServerProxyBootstrap.currentVoicePortPlan();
        if (plan == null) {
            return;
        }
        List<Integer> ports = plan.ports();
        int count = Math.min(ports.size(), MAX_PORTS);
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeByte(WIRE_VERSION);
        out.writeUtf(plan.transport(), 32);
        out.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            out.writeVarInt(ports.get(i));
        }
        ServerPlayNetworking.send(player, VOICE_PORT_SYNC_ID, out);
    }
}
