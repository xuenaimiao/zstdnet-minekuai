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
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * play 阶段「字典自动下发」（MC 1.20.1 Fabric 网络 API）。见 {@link ClientDictionaryStore} 的说明。
 */
public final class DictionarySync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation DICT_SYNC_ID = new ResourceLocation(Zstdnet.MODID, "dict_sync");
    private static final ResourceLocation DICT_WANT_ID = new ResourceLocation(Zstdnet.MODID, "dict_want");
    private static final byte[] EMPTY = new byte[0];
    private static final int MAX_BYTES = (int) ClientDictionaryStore.MAX_DICTIONARY_BYTES;

    private static boolean initialized;
    private static boolean clientInitialized;

    private DictionarySync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ServerPlayNetworking.registerGlobalReceiver(DICT_WANT_ID, (server, player, handler, buf, responseSender) -> {
            long dictId = buf.readLong();
            server.execute(() -> {
                byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
                if (dictionary != null && Zstd.getDictIdFromDict(dictionary) == dictId) {
                    FriendlyByteBuf out = PacketByteBufs.create();
                    out.writeLong(dictId);
                    out.writeByteArray(dictionary);
                    ServerPlayNetworking.send(player, DICT_SYNC_ID, out);
                }
            });
        });
    }

    public static void initClient() {
        if (clientInitialized || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        clientInitialized = true;

        ClientPlayNetworking.registerGlobalReceiver(DICT_SYNC_ID, (client, handler, buf, responseSender) -> {
            long dictId = buf.readLong();
            byte[] data = buf.readByteArray(MAX_BYTES);
            client.execute(() -> handleDictSync(dictId, data));
        });
    }

    /** 服务端：玩家进服后，若配置了字典则告知其 dict id（空 data = 公告）。 */
    public static void announce(ServerPlayer player) {
        byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
        if (dictionary == null) {
            return;
        }
        long dictId = Zstd.getDictIdFromDict(dictionary);
        if (dictId == 0L) {
            return;
        }
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeLong(dictId);
        out.writeByteArray(EMPTY);
        ServerPlayNetworking.send(player, DICT_SYNC_ID, out);
    }

    private static void handleDictSync(long dictId, byte[] data) {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = Platforms.get().configDir();

        if (data.length == 0) {
            if (dictId != 0L && !ClientDictionaryStore.hasDictionary(configDir, dictId)) {
                FriendlyByteBuf out = PacketByteBufs.create();
                out.writeLong(dictId);
                ClientPlayNetworking.send(DICT_WANT_ID, out);
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
            stored = ClientDictionaryStore.store(configDir, address, dictId, data);
        } catch (Exception ex) {
            LOGGER.error("[zstdnet-client] failed to store auto dictionary for {}: {}", address, ex.toString());
            stored = false;
        }
        if (stored && minecraft.getConnection() != null) {
            LOGGER.info("[zstdnet-client] downloaded dictionary id {} for {}; reconnecting to enable it", dictId, address);
            minecraft.getConnection().getConnection().disconnect(Component.literal(
                "ZstdNet：已下载该服务器的压缩字典，请重新进入服务器以启用字典压缩。\n"
                    + "Downloaded the server's compression dictionary — please rejoin to enable it."));
        }
    }
}
