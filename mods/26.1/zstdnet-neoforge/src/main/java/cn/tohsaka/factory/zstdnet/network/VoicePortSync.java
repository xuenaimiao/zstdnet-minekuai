/*
 * Copyright (c) 2026 wish (original author, MIT — https://github.com/wish131400/zstdnet)
 * Copyright (c) 2026 xuenai · 麦块联机 / MineKuai (https://minekuai.com)
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is a derivative work of the MIT-licensed ZstdNet by wish. wish's
 * original portions remain under the MIT License (see the LICENSE file); that
 * upstream grant is preserved and not revoked.
 *
 * This project as a whole — and all modifications and additions by xuenai — is
 * licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0
 * International License (CC BY-NC-SA 4.0). You may share and adapt it for
 * NON-COMMERCIAL purposes only, must give appropriate credit and retain the
 * copyright notices above, and must distribute your contributions under this
 * same license (share-alike, source included).
 *
 * You should have received a copy of the license along with ZstdNet.
 * If not, see <https://creativecommons.org/licenses/by-nc-sa/4.0/>.
 */

package cn.tohsaka.factory.zstdnet.network;

import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.VoicePortPlan;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * play 阶段「语音端口下发」（MC 1.21.1 NeoForge payload）。
 *
 * <p>服务端探测到后端语音 mod 的独立端口后，玩家进服时把「传输方式 + 端口列表」下发给客户端；
 * 客户端据此在本机为这些端口开监听（tunnel 打标隧道 / bridge 直连），从而零配置兼容各类语音 mod。
 * 列表顺序即隧道 channelId，两端必须一致。旧服务端不发本消息、旧客户端不认本消息时，行为完全同历史版本。</p>
 */
public final class VoicePortSync {
    private static final String PROTOCOL_VERSION = "1";
    private static final int MAX_PORTS = 256;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private VoicePortSync() {
    }

    public static void init(IEventBus modEventBus) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        modEventBus.addListener(VoicePortSync::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(VoicePortListMessage.TYPE, VoicePortListMessage.STREAM_CODEC, VoicePortListMessage::handle);
    }

    /** 服务端：玩家进服后下发当前语音端口计划（空列表也下发，让客户端撤销旧监听）。 */
    public static void send(ServerPlayer player) {
        VoicePortPlan plan = ServerProxyBootstrap.currentVoicePortPlan();
        if (plan == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new VoicePortListMessage(plan.transport(), plan.ports()));
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

        private static void handle(VoicePortListMessage message, IPayloadContext context) {
            context.enqueueWork(() -> ClientProxyPublisher.acceptVoicePortList(message.transport(), message.ports()));
        }
    }
}
