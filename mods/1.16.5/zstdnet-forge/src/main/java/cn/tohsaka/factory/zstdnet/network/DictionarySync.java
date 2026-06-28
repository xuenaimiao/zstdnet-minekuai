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
import cn.tohsaka.factory.zstdnet.core.compress.ClientDictionaryStore;
import cn.tohsaka.factory.zstdnet.core.compress.ZstdCodecs;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * play 阶段「字典自动下发」（MC 1.19.2 Forge SimpleChannel）。见 {@link ClientDictionaryStore} 的说明。
 */
public final class DictionarySync {
    private static final Logger LOGGER = LoggerFactory.getLogger(DictionarySync.class);
    private static final String PROTOCOL_VERSION = "1";
    private static final byte[] EMPTY = new byte[0];
    private static final int MAX_BYTES = (int) ClientDictionaryStore.MAX_DICTIONARY_BYTES;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(Zstdnet.MODID, "dict_sync"),
        () -> PROTOCOL_VERSION,
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version),
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version)
    );

    private static boolean initialized;

    private DictionarySync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        int id = 0;
        CHANNEL.messageBuilder(DictSyncMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(DictSyncMessage::encode)
            .decoder(DictSyncMessage::decode)
            .consumer(DictSyncMessage::handle)
            .add();
        CHANNEL.messageBuilder(DictWantMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(DictWantMessage::encode)
            .decoder(DictWantMessage::decode)
            .consumer(DictWantMessage::handle)
            .add();
    }

    /** 服务端：玩家进服后，若配置了字典则告知其 dict id（空 data = 公告）。 */
    public static void announce(ServerPlayerEntity player) {
        init();
        byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
        if (dictionary == null) {
            return;
        }
        long dictId = ZstdCodecs.getDictIdFromDict(dictionary);
        if (dictId != 0L) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DictSyncMessage(dictId, EMPTY));
        }
    }

    private static void handleDictSync(DictSyncMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = Platforms.get().configDir();

        if (message.data().length == 0) {
            if (message.dictId() != 0L && !ClientDictionaryStore.hasDictionary(configDir, message.dictId())) {
                CHANNEL.sendToServer(new DictWantMessage(message.dictId()));
            }
            return;
        }

        ServerData server = minecraft.getCurrentServerData();
        String address = server != null ? server.serverIP : null;
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        boolean stored;
        try {
            stored = ClientDictionaryStore.store(configDir, address, message.dictId(), message.data());
        } catch (Exception ex) {
            LOGGER.error("[zstdnet-client] failed to store auto dictionary for {}: {}", address, ex.toString());
            stored = false;
        }
        if (stored && minecraft.getConnection() != null) {
            LOGGER.info("[zstdnet-client] downloaded dictionary id {} for {}; reconnecting to enable it", message.dictId(), address);
            minecraft.getConnection().getNetworkManager().closeChannel(new StringTextComponent(
                "ZstdNet：已下载该服务器的压缩字典，请重新进入服务器以启用字典压缩。\n"
                    + "Downloaded the server's compression dictionary — please rejoin to enable it."));
        }
    }

    private static final class DictSyncMessage {
        private final long dictId;
        private final byte[] data;

        private DictSyncMessage(long dictId, byte[] data) {
            this.dictId = dictId;
            this.data = data;
        }

        private long dictId() {
            return dictId;
        }

        private byte[] data() {
            return data;
        }

        private static DictSyncMessage decode(PacketBuffer buf) {
            return new DictSyncMessage(buf.readLong(), buf.readByteArray(MAX_BYTES));
        }

        private static void encode(DictSyncMessage message, PacketBuffer buf) {
            buf.writeLong(message.dictId());
            buf.writeByteArray(message.data());
        }

        private static void handle(DictSyncMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> handleDictSync(message));
            supplier.get().setPacketHandled(true);
        }
    }

    private static final class DictWantMessage {
        private final long dictId;

        private DictWantMessage(long dictId) {
            this.dictId = dictId;
        }

        private long dictId() {
            return dictId;
        }

        private static DictWantMessage decode(PacketBuffer buf) {
            return new DictWantMessage(buf.readLong());
        }

        private static void encode(DictWantMessage message, PacketBuffer buf) {
            buf.writeLong(message.dictId());
        }

        private static void handle(DictWantMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayerEntity player = context.getSender();
                if (player == null) {
                    return;
                }
                byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
                if (dictionary != null && ZstdCodecs.getDictIdFromDict(dictionary) == message.dictId()) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DictSyncMessage(message.dictId(), dictionary));
                }
            });
            context.setPacketHandled(true);
        }
    }
}
