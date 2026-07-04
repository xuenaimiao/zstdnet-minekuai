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
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;

    private static final Logger LOGGER = LoggerFactory.getLogger(LanCompressionSync.class);
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

    public static void requestCompressionUpgrade(ServerPlayerEntity player) {
        init();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PrepareMessage(LAN_THRESHOLD));
        LOGGER.info(
            "[zstdnet-server] requested LAN compression threshold {} for {}.",
            LAN_THRESHOLD,
            player.getGameProfile().getName()
        );
    }

    public static void sendServerHudSnapshot(ServerPlayerEntity player, ServerProxyBootstrap.ServerHudSnapshot snapshot) {
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
        minecraft.getConnection().getNetworkManager().setCompressionThreshold(threshold);
        LOGGER.info("[zstdnet-client] compression threshold switched to {}.", threshold);
    }

    private static final class PrepareMessage {
        private final int threshold;

        private PrepareMessage(int threshold) {
            this.threshold = threshold;
        }

        private static PrepareMessage decode(PacketBuffer buf) {
            return new PrepareMessage(buf.readVarInt());
        }

        private static void encode(PrepareMessage message, PacketBuffer buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(PrepareMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> CHANNEL.sendToServer(new ReadyMessage(message.threshold)));
            supplier.get().setPacketHandled(true);
        }
    }

    private static final class ReadyMessage {
        private final int threshold;

        private ReadyMessage(int threshold) {
            this.threshold = threshold;
        }

        private static ReadyMessage decode(PacketBuffer buf) {
            return new ReadyMessage(buf.readVarInt());
        }

        private static void encode(ReadyMessage message, PacketBuffer buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(ReadyMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayerEntity player = context.getSender();
                if (player == null) {
                    return;
                }
                player.connection.netManager.setCompressionThreshold(message.threshold);
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

    private static final class ActivateMessage {
        private final int threshold;

        private ActivateMessage(int threshold) {
            this.threshold = threshold;
        }

        private static ActivateMessage decode(PacketBuffer buf) {
            return new ActivateMessage(buf.readVarInt());
        }

        private static void encode(ActivateMessage message, PacketBuffer buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(ActivateMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> applyClientThreshold(message.threshold));
            supplier.get().setPacketHandled(true);
        }
    }

    private static final class ServerHudMessage {
        private final String mode;
        private final String listenHost;
        private final int listenPort;
        private final long rawBytes;
        private final long zstdBytes;
        private final long rawUpBytes;
        private final long rawDownBytes;
        private final long zstdUpBytes;
        private final long zstdDownBytes;
        private final long rawUpRate;
        private final long rawDownRate;
        private final long zstdUpRate;
        private final long zstdDownRate;
        private final long rawRate;
        private final long zstdRate;
        private final double ratioPercent;
        private final int connections;

        private ServerHudMessage(
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
            this.mode = mode;
            this.listenHost = listenHost;
            this.listenPort = listenPort;
            this.rawBytes = rawBytes;
            this.zstdBytes = zstdBytes;
            this.rawUpBytes = rawUpBytes;
            this.rawDownBytes = rawDownBytes;
            this.zstdUpBytes = zstdUpBytes;
            this.zstdDownBytes = zstdDownBytes;
            this.rawUpRate = rawUpRate;
            this.rawDownRate = rawDownRate;
            this.zstdUpRate = zstdUpRate;
            this.zstdDownRate = zstdDownRate;
            this.rawRate = rawRate;
            this.zstdRate = zstdRate;
            this.ratioPercent = ratioPercent;
            this.connections = connections;
        }

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

        private static ServerHudMessage decode(PacketBuffer buf) {
            return new ServerHudMessage(
                buf.readString(),
                buf.readString(),
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

        private static void encode(ServerHudMessage message, PacketBuffer buf) {
            buf.writeString(message.mode);
            buf.writeString(message.listenHost);
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
