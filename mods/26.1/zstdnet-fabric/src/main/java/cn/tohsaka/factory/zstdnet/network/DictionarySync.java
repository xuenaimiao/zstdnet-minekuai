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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * play 阶段「字典自动下发」：服务端在玩家进服后告知其字典 id；客户端若没有则向服务端请求，
 * 拿到后缓存并按服务器记录映射，再主动断开提示玩家重连，使字典从下一次连接的第一帧起生效。
 * 见 {@link ClientDictionaryStore}。
 */
public final class DictionarySync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final byte[] EMPTY = new byte[0];
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean CLIENT_INITIALIZED = new AtomicBoolean(false);

    private DictionarySync() {
    }

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        PayloadTypeRegistry.clientboundPlay().register(DictSyncMessage.TYPE, DictSyncMessage.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DictWantMessage.TYPE, DictWantMessage.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(DictWantMessage.TYPE, (message, context) ->
            context.server().execute(() -> {
                byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
                if (dictionary != null && Zstd.getDictIdFromDict(dictionary) == message.dictId()) {
                    ServerPlayNetworking.send(context.player(), new DictSyncMessage(message.dictId(), dictionary));
                }
            }));
    }

    public static void initClient() {
        if (!CLIENT_INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        ClientPlayNetworking.registerGlobalReceiver(DictSyncMessage.TYPE, (message, context) ->
            context.client().execute(() -> handleDictSync(message)));
    }

    /** 服务端：玩家进服后，若配置了字典则告知其 dict id（不直接发字节，省带宽）。 */
    public static void announce(ServerPlayer player) {
        byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
        if (dictionary == null) {
            return;
        }
        long dictId = Zstd.getDictIdFromDict(dictionary);
        if (dictId != 0L) {
            ServerPlayNetworking.send(player, new DictSyncMessage(dictId, EMPTY));
        }
    }

    private static void handleDictSync(DictSyncMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = Platforms.get().configDir();

        if (message.data().length == 0) {
            // 公告：本端若没有该字典则请求下发。
            if (message.dictId() != 0L && !ClientDictionaryStore.hasDictionary(configDir, message.dictId())) {
                ClientPlayNetworking.send(new DictWantMessage(message.dictId()));
            }
            return;
        }

        // 收到字典字节：缓存并映射到当前服务器，成功后断开提示重连。
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
            minecraft.getConnection().getConnection().disconnect(Component.literal(
                "ZstdNet：已下载该服务器的压缩字典，请重新进入服务器以启用字典压缩。\n"
                    + "Downloaded the server's compression dictionary — please rejoin to enable it."));
        }
    }

    private record DictSyncMessage(long dictId, byte[] data) implements CustomPacketPayload {
        private static final Identifier ID = Identifier.fromNamespaceAndPath(Zstdnet.MODID, "dict_sync");
        private static final Type<DictSyncMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, DictSyncMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG,
            DictSyncMessage::dictId,
            ByteBufCodecs.byteArray((int) ClientDictionaryStore.MAX_DICTIONARY_BYTES),
            DictSyncMessage::data,
            DictSyncMessage::new
        );

        @Override
        public Type<DictSyncMessage> type() {
            return TYPE;
        }
    }

    private record DictWantMessage(long dictId) implements CustomPacketPayload {
        private static final Identifier ID = Identifier.fromNamespaceAndPath(Zstdnet.MODID, "dict_want");
        private static final Type<DictWantMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, DictWantMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG,
            DictWantMessage::dictId,
            DictWantMessage::new
        );

        @Override
        public Type<DictWantMessage> type() {
            return TYPE;
        }
    }
}
