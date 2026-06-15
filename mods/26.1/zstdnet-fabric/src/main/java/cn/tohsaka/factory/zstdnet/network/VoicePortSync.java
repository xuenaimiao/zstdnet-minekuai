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
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * play 阶段「语音端口下发」（MC 1.21.1 Fabric payload）。
 *
 * <p>服务端探测到后端语音 mod 的独立端口后，玩家进服时把「传输方式 + 端口列表」下发给客户端；
 * 客户端据此在本机为这些端口开监听（tunnel 打标隧道 / bridge 直连），从而零配置兼容各类语音 mod。
 * 列表顺序即隧道 channelId，两端必须一致。旧服务端不发本消息、旧客户端不认本消息时，行为完全同历史版本。</p>
 */
public final class VoicePortSync {
    private static final int MAX_PORTS = 256;

    private static boolean initialized;
    private static boolean clientInitialized;

    private VoicePortSync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        PayloadTypeRegistry.clientboundPlay().register(VoicePortListMessage.TYPE, VoicePortListMessage.STREAM_CODEC);
    }

    public static void initClient() {
        if (clientInitialized || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        clientInitialized = true;
        ClientPlayNetworking.registerGlobalReceiver(VoicePortListMessage.TYPE, (message, context) ->
            context.client().execute(() -> ClientProxyPublisher.acceptVoicePortList(message.transport(), message.ports())));
    }

    /** 服务端：玩家进服后下发当前语音端口计划（空列表也下发，让客户端撤销旧监听）。 */
    public static void send(ServerPlayer player) {
        VoicePortPlan plan = ServerProxyBootstrap.currentVoicePortPlan();
        if (plan == null) {
            return;
        }
        ServerPlayNetworking.send(player, new VoicePortListMessage(plan.transport(), plan.ports()));
    }

    private record VoicePortListMessage(String transport, List<Integer> ports) implements CustomPacketPayload {
        private static final Identifier ID = Identifier.fromNamespaceAndPath(Zstdnet.MODID, "voice_port_sync");
        private static final Type<VoicePortListMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, VoicePortListMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            VoicePortListMessage::transport,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_PORTS)),
            VoicePortListMessage::ports,
            VoicePortListMessage::new
        );

        @Override
        public Type<VoicePortListMessage> type() {
            return TYPE;
        }
    }
}
