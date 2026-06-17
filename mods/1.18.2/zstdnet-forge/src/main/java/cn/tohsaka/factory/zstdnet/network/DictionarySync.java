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
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.github.luben.zstd.Zstd;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * play 阶段「字典自动下发」（MC 1.19.2 Forge SimpleChannel）。见 {@link ClientDictionaryStore} 的说明。
 */
public final class DictionarySync {
    private static final Logger LOGGER = LogUtils.getLogger();
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
    public static void announce(ServerPlayer player) {
        init();
        byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
        if (dictionary == null) {
            return;
        }
        long dictId = Zstd.getDictIdFromDict(dictionary);
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

        ServerData server = minecraft.getCurrentServer();
        String address = server != null ? server.ip : null;
        if (address == null || address.isBlank()) {
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
            minecraft.getConnection().getConnection().disconnect(new TextComponent(
                "ZstdNet：已下载该服务器的压缩字典，请重新进入服务器以启用字典压缩。\n"
                    + "Downloaded the server's compression dictionary — please rejoin to enable it."));
        }
    }

    private record DictSyncMessage(long dictId, byte[] data) {
        private static DictSyncMessage decode(FriendlyByteBuf buf) {
            return new DictSyncMessage(buf.readLong(), buf.readByteArray(MAX_BYTES));
        }

        private static void encode(DictSyncMessage message, FriendlyByteBuf buf) {
            buf.writeLong(message.dictId());
            buf.writeByteArray(message.data());
        }

        private static void handle(DictSyncMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> handleDictSync(message));
            supplier.get().setPacketHandled(true);
        }
    }

    private record DictWantMessage(long dictId) {
        private static DictWantMessage decode(FriendlyByteBuf buf) {
            return new DictWantMessage(buf.readLong());
        }

        private static void encode(DictWantMessage message, FriendlyByteBuf buf) {
            buf.writeLong(message.dictId());
        }

        private static void handle(DictWantMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
                if (dictionary != null && Zstd.getDictIdFromDict(dictionary) == message.dictId()) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DictSyncMessage(message.dictId(), dictionary));
                }
            });
            context.setPacketHandled(true);
        }
    }
}
