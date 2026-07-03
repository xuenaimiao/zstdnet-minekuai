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

import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier;
import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.VerifiedProfile;
import cn.tohsaka.factory.zstdnet.auth.PremiumAuthProtocol;
import cn.tohsaka.factory.zstdnet.auth.PremiumAuthState;
import cn.tohsaka.factory.zstdnet.auth.PremiumVerificationService;
import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import cn.tohsaka.factory.zstdnet.mixin.ServerLoginPacketListenerImplAccessor;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.User;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「登录阶段正版验证」（MC 1.21.1 Fabric 网络 API）。见 {@link PremiumAuthProtocol} 的机制说明。
 * <p>
 * 与 1.20.1 实现的唯一区别：登录档案字段名 {@code authenticatedProfile}（accessor 处理），
 * 以及 authlib 6.x 的 {@code joinServer(UUID, token, serverId)} 取 UUID 而非 GameProfile。
 */
public final class PremiumAuthSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CHANNEL =
        ResourceLocation.fromNamespaceAndPath(PremiumAuthProtocol.CHANNEL_NAMESPACE, PremiumAuthProtocol.CHANNEL_PATH);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zstdnet-premium-auth");
        t.setDaemon(true);
        return t;
    });
    private static final Map<ServerLoginPacketListenerImpl, byte[]> PENDING =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean initialized;
    private static boolean clientInitialized;

    private PremiumAuthSync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            if (!PremiumAuthState.isEnabled()) {
                return;
            }
            Connection connection = ((ServerLoginPacketListenerImplAccessor) handler).zstdnet$getConnection();
            if (connection != null && connection.isMemoryConnection()) {
                return;
            }
            byte[] nonce = new byte[PremiumAuthProtocol.NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            PENDING.put(handler, nonce);
            FriendlyByteBuf out = PacketByteBufs.create();
            out.writeBytes(PremiumAuthProtocol.encodeChallenge(nonce));
            sender.sendPacket(CHANNEL, out);
        });

        ServerLoginNetworking.registerGlobalReceiver(CHANNEL,
            (server, handler, understood, buf, synchronizer, responseSender) -> {
                byte[] nonce = PENDING.remove(handler);
                String username = profileName(handler);
                if (!understood) {
                    applyPolicyOnFailure(handler, username, "client does not have ZstdNet premium verification");
                    return;
                }
                PremiumAuthProtocol.Answer answer = PremiumAuthProtocol.decodeAnswer(readAll(buf));
                if (!answer.authenticated() || nonce == null || username == null) {
                    applyPolicyOnFailure(handler, username, "client reported no valid premium session");
                    return;
                }
                String serverId = PremiumAuthProtocol.serverIdFromNonce(nonce);
                String clientIp = PremiumAuthState.passRealIp() ? clientIp(handler) : null;
                synchronizer.waitFor(CompletableFuture.runAsync(() -> {
                    VerifiedProfile profile = PremiumVerificationService.verify(username, serverId, clientIp);
                    if (profile != null) {
                        ((ServerLoginPacketListenerImplAccessor) handler).zstdnet$setGameProfile(buildProfile(profile));
                        LOGGER.info("[zstdnet-server] verified premium player {} -> {}", username, profile.id());
                    } else {
                        applyPolicyOnFailure(handler, username, "session server did not confirm " + username);
                    }
                }, EXECUTOR));
            });
    }

    public static void initClient() {
        if (clientInitialized || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        clientInitialized = true;

        ClientLoginNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buf, listenerAdder) -> {
            byte[] nonce = PremiumAuthProtocol.decodeChallenge(readAll(buf));
            return CompletableFuture.supplyAsync(() -> {
                boolean authenticated = false;
                String source = "";
                if (nonce != null) {
                    try {
                        User user = client.getUser();
                        String serverId = PremiumAuthProtocol.serverIdFromNonce(nonce);
                        client.getMinecraftSessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
                        authenticated = true;
                        source = "mojang";
                    } catch (Throwable t) {
                        LOGGER.debug("[zstdnet-client] premium joinServer failed (offline session?): {}", t.toString());
                    }
                }
                FriendlyByteBuf out = PacketByteBufs.create();
                out.writeBytes(PremiumAuthProtocol.encodeAnswer(authenticated, source));
                return out;
            }, EXECUTOR);
        });
    }

    /** 验证不通过：由共享策略决定——strict / 正版身份保护 → 断开，其余宽松放行（日志在策略内打印）。 */
    private static void applyPolicyOnFailure(ServerLoginPacketListenerImpl handler, String username, String reason) {
        String rejection = PremiumVerificationService.rejectionMessage(username, reason);
        if (rejection != null) {
            handler.disconnect(Component.literal(rejection));
        }
    }

    private static GameProfile buildProfile(VerifiedProfile profile) {
        GameProfile gameProfile = new GameProfile(profile.id(), profile.name());
        for (MojangPremiumVerifier.Property property : profile.properties()) {
            gameProfile.getProperties().put(property.name(),
                new Property(property.name(), property.value(), property.signature()));
        }
        return gameProfile;
    }

    private static String profileName(ServerLoginPacketListenerImpl handler) {
        GameProfile profile = ((ServerLoginPacketListenerImplAccessor) handler).zstdnet$getGameProfile();
        return profile != null ? profile.getName() : null;
    }

    private static String clientIp(ServerLoginPacketListenerImpl handler) {
        Connection connection = ((ServerLoginPacketListenerImplAccessor) handler).zstdnet$getConnection();
        if (connection == null) {
            return null;
        }
        SocketAddress address = ServerRealIpHooks.getRemoteAddress(connection, connection.getRemoteAddress());
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
