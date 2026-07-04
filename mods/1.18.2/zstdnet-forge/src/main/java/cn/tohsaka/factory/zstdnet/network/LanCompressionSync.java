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

import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(Zstdnet.MODID, "lan_compression"),
        () -> PROTOCOL_VERSION,
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version),
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version)
    );

    private static boolean initialized;

    private LanCompressionSync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        int id = 0;
        CHANNEL.messageBuilder(PrepareMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(PrepareMessage::encode)
            .decoder(PrepareMessage::decode)
            .consumer(PrepareMessage::handle)
            .add();
        CHANNEL.messageBuilder(ReadyMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ReadyMessage::encode)
            .decoder(ReadyMessage::decode)
            .consumer(ReadyMessage::handle)
            .add();
        CHANNEL.messageBuilder(ActivateMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ActivateMessage::encode)
            .decoder(ActivateMessage::decode)
            .consumer(ActivateMessage::handle)
            .add();
        CHANNEL.messageBuilder(ServerHudMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ServerHudMessage::encode)
            .decoder(ServerHudMessage::decode)
            .consumer(ServerHudMessage::handle)
            .add();
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        init();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PrepareMessage(LAN_THRESHOLD));
        LOGGER.info(
            "[zstdnet-server] requested LAN compression threshold {} for {}.",
            LAN_THRESHOLD,
            player.getGameProfile().getName()
        );
    }

    public static void sendServerHudSnapshot(ServerPlayer player, ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        init();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ServerHudMessage.from(snapshot));
    }

    private static void applyClientThreshold(int threshold) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().getConnection().setupCompression(threshold, false);
        LOGGER.info("[zstdnet-client] compression threshold switched to {}.", threshold);
    }

    private record PrepareMessage(int threshold) {
        private static PrepareMessage decode(FriendlyByteBuf buf) {
            return new PrepareMessage(buf.readVarInt());
        }

        private static void encode(PrepareMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(PrepareMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> CHANNEL.sendToServer(new ReadyMessage(message.threshold)));
            supplier.get().setPacketHandled(true);
        }
    }

    private record ReadyMessage(int threshold) {
        private static ReadyMessage decode(FriendlyByteBuf buf) {
            return new ReadyMessage(buf.readVarInt());
        }

        private static void encode(ReadyMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(ReadyMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                player.connection.connection.setupCompression(message.threshold, true);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ActivateMessage(message.threshold));
                LOGGER.info(
                    "[zstdnet-server] LAN compression threshold {} activated for {}.",
                    message.threshold,
                    player.getGameProfile().getName()
                );
            });
            context.setPacketHandled(true);
        }
    }

    private record ActivateMessage(int threshold) {
        private static ActivateMessage decode(FriendlyByteBuf buf) {
            return new ActivateMessage(buf.readVarInt());
        }

        private static void encode(ActivateMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(ActivateMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> applyClientThreshold(message.threshold));
            supplier.get().setPacketHandled(true);
        }
    }

    private record ServerHudMessage(
        String mode,
        String listenHost,
        int listenPort,
        long rawBytes,
        long zstdBytes,
        long rawUpBytes,
        long rawDownBytes,
        long zstdUpBytes,
        long zstdDownBytes,
        long rawUpRate,
        long rawDownRate,
        long zstdUpRate,
        long zstdDownRate,
        long rawRate,
        long zstdRate,
        double ratioPercent,
        int connections
    ) {
        private static ServerHudMessage from(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
            return new ServerHudMessage(
                snapshot.mode(),
                snapshot.listenHost(),
                snapshot.listenPort(),
                snapshot.rawBytes(),
                snapshot.zstdBytes(),
                snapshot.rawUpBytes(),
                snapshot.rawDownBytes(),
                snapshot.zstdUpBytes(),
                snapshot.zstdDownBytes(),
                snapshot.rawUpRate(),
                snapshot.rawDownRate(),
                snapshot.zstdUpRate(),
                snapshot.zstdDownRate(),
                snapshot.rawRate(),
                snapshot.zstdRate(),
                snapshot.ratioPercent(),
                snapshot.connections()
            );
        }

        private static ServerHudMessage decode(FriendlyByteBuf buf) {
            return new ServerHudMessage(
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readDouble(),
                buf.readVarInt()
            );
        }

        private static void encode(ServerHudMessage message, FriendlyByteBuf buf) {
            buf.writeUtf(message.mode);
            buf.writeUtf(message.listenHost);
            buf.writeVarInt(message.listenPort);
            buf.writeLong(message.rawBytes);
            buf.writeLong(message.zstdBytes);
            buf.writeLong(message.rawUpBytes);
            buf.writeLong(message.rawDownBytes);
            buf.writeLong(message.zstdUpBytes);
            buf.writeLong(message.zstdDownBytes);
            buf.writeLong(message.rawUpRate);
            buf.writeLong(message.rawDownRate);
            buf.writeLong(message.zstdUpRate);
            buf.writeLong(message.zstdDownRate);
            buf.writeLong(message.rawRate);
            buf.writeLong(message.zstdRate);
            buf.writeDouble(message.ratioPercent);
            buf.writeVarInt(message.connections);
        }

        private static void handle(ServerHudMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> ClientProxyPublisher.acceptRemoteServerHudSnapshot(message.toSnapshot()));
            supplier.get().setPacketHandled(true);
        }

        private ServerProxyBootstrap.ServerHudSnapshot toSnapshot() {
            return new ServerProxyBootstrap.ServerHudSnapshot(
                mode,
                listenHost,
                listenPort,
                rawBytes,
                zstdBytes,
                rawUpBytes,
                rawDownBytes,
                zstdUpBytes,
                zstdDownBytes,
                rawUpRate,
                rawDownRate,
                zstdUpRate,
                zstdDownRate,
                rawRate,
                zstdRate,
                ratioPercent,
                connections
            );
        }
    }
}
