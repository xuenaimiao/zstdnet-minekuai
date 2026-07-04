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
import cn.tohsaka.factory.zstdnet.core.compress.ClientDictionaryStore;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.github.luben.zstd.Zstd;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * play 阶段「字典自动下发」。见 fabric 同名类与 {@link ClientDictionaryStore} 的说明。
 */
public final class DictionarySync {
    private static final Logger LOGGER = LoggerFactory.getLogger(DictionarySync.class);
    private static final String PROTOCOL_VERSION = "1";
    private static final byte[] EMPTY = new byte[0];
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private DictionarySync() {
    }

    public static void init(IEventBus modEventBus) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        modEventBus.addListener(DictionarySync::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(DictSyncMessage.TYPE, DictSyncMessage.STREAM_CODEC, DictSyncMessage::handle);
        registrar.playToServer(DictWantMessage.TYPE, DictWantMessage.STREAM_CODEC, DictWantMessage::handle);
    }

    /** 服务端：玩家进服后，若配置了字典则告知其 dict id。 */
    public static void announce(ServerPlayer player) {
        byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
        if (dictionary == null) {
            return;
        }
        long dictId = Zstd.getDictIdFromDict(dictionary);
        if (dictId != 0L) {
            PacketDistributor.sendToPlayer(player, new DictSyncMessage(dictId, EMPTY));
        }
    }

    private static void handleDictSync(DictSyncMessage message, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = Platforms.get().configDir();

        if (message.data().length == 0) {
            if (message.dictId() != 0L && !ClientDictionaryStore.hasDictionary(configDir, message.dictId())) {
                context.reply(new DictWantMessage(message.dictId()));
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

        private static void handle(DictSyncMessage message, IPayloadContext context) {
            context.enqueueWork(() -> handleDictSync(message, context));
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

        private static void handle(DictWantMessage message, IPayloadContext context) {
            context.enqueueWork(() -> {
                byte[] dictionary = ServerProxyConfigFile.loadDictionaryBytes();
                if (dictionary != null
                    && Zstd.getDictIdFromDict(dictionary) == message.dictId()
                    && context.player() instanceof ServerPlayer player) {
                    PacketDistributor.sendToPlayer(player, new DictSyncMessage(message.dictId(), dictionary));
                }
            });
        }
    }
}
