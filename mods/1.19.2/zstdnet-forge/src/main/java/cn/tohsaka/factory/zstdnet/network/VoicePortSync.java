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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * play 阶段「语音端口下发」（MC 1.19.2 Forge SimpleChannel）。
 *
 * <p>服务端探测到后端语音 mod 的独立端口后，玩家进服时把「传输方式 + 端口列表」下发给客户端；
 * 客户端据此在本机为这些端口开监听（tunnel 打标隧道 / bridge 直连），从而零配置兼容各类语音 mod。
 * 列表顺序即隧道 channelId，两端必须一致。旧服务端不发本消息、旧客户端不认本消息时，行为完全同历史版本。</p>
 */
public final class VoicePortSync {
    private static final String PROTOCOL_VERSION = "1";
    private static final byte WIRE_VERSION = 1;
    private static final int MAX_PORTS = 256;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(Zstdnet.MODID, "voice_port_sync"),
        () -> PROTOCOL_VERSION,
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version),
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version)
    );

    private static boolean initialized;

    private VoicePortSync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        int id = 0;
        CHANNEL.messageBuilder(VoicePortListMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(VoicePortListMessage::encode)
            .decoder(VoicePortListMessage::decode)
            .consumerMainThread(VoicePortListMessage::handle)
            .add();
    }

    /** 服务端：玩家进服后下发当前语音端口计划（空列表也下发，让客户端撤销旧监听）。 */
    public static void send(ServerPlayer player) {
        init();
        VoicePortPlan plan = ServerProxyBootstrap.currentVoicePortPlan();
        if (plan == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new VoicePortListMessage(plan.transport(), plan.ports()));
    }

    private record VoicePortListMessage(String transport, List<Integer> ports) {
        private static VoicePortListMessage decode(FriendlyByteBuf buf) {
            buf.readByte(); // wire version (reserved for future use)
            String transport = buf.readUtf(32);
            int count = Math.min(buf.readVarInt(), MAX_PORTS);
            List<Integer> ports = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                ports.add(buf.readVarInt());
            }
            return new VoicePortListMessage(transport, ports);
        }

        private static void encode(VoicePortListMessage message, FriendlyByteBuf buf) {
            buf.writeByte(WIRE_VERSION);
            buf.writeUtf(message.transport(), 32);
            List<Integer> ports = message.ports();
            int count = Math.min(ports.size(), MAX_PORTS);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeVarInt(ports.get(i));
            }
        }

        private static void handle(VoicePortListMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> ClientProxyPublisher.acceptVoicePortList(message.transport(), message.ports()));
            supplier.get().setPacketHandled(true);
        }
    }
}
