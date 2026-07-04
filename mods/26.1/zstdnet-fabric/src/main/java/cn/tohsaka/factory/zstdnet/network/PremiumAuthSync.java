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

import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier;
import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.VerifiedProfile;
import cn.tohsaka.factory.zstdnet.auth.PremiumAuthProtocol;
import cn.tohsaka.factory.zstdnet.auth.PremiumAuthState;
import cn.tohsaka.factory.zstdnet.auth.PremiumVerificationService;
import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import cn.tohsaka.factory.zstdnet.mixin.ServerLoginPacketListenerImplAccessor;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.User;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * 「登录阶段正版验证」（MC 26.1 Fabric 网络 API）。见 {@link PremiumAuthProtocol} 的机制说明。
 * <p>
 * 26.1（非混淆）差异：{@code ResourceLocation} 改名 {@code Identifier}；fabric-api 无 {@code PacketByteBufs}，
 * 用 {@code new FriendlyByteBuf(Unpooled.buffer())}。登录档案字段仍为 {@code authenticatedProfile}，
 * authlib 7.x {@code joinServer(UUID, token, serverId)}。
 */
public final class PremiumAuthSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier CHANNEL =
        Identifier.fromNamespaceAndPath(PremiumAuthProtocol.CHANNEL_NAMESPACE, PremiumAuthProtocol.CHANNEL_PATH);
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
            FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
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
                    try {
                        VerifiedProfile profile = PremiumVerificationService.verify(username, serverId, clientIp);
                        if (profile != null) {
                            ((ServerLoginPacketListenerImplAccessor) handler).zstdnet$setGameProfile(buildProfile(profile));
                            LOGGER.info("[zstdnet-server] verified premium player {} -> {}", username, profile.id());
                        } else {
                            applyPolicyOnFailure(handler, username, "session server did not confirm " + username);
                        }
                    } catch (Throwable t) {
                        // 关键：fabric 登录 addon 的 queryTick() 会把本 future 的异常收集后【静默丢弃】，
                        // 既不记日志也不断开 —— 玩家会以离线 UUID 进服（数据/背包丢失且无任何线索）。
                        // 故必须在此自行记录，否则任何验证期异常都将成为「无声的数据丢失」。
                        LOGGER.error("[zstdnet-server] premium verification failed for {} (falling back to offline identity; player data may differ): {}",
                            username, t.toString(), t);
                        applyPolicyOnFailure(handler, username, "verification error: " + t);
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
                        client.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
                        authenticated = true;
                        source = "mojang";
                    } catch (Throwable t) {
                        LOGGER.debug("[zstdnet-client] premium joinServer failed (offline session?): {}", t.toString());
                    }
                }
                FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
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
        // authlib 7.x（MC 26.1）的 GameProfile 是 record，其 PropertyMap 恒为不可变
        // （PropertyMap 构造即 ImmutableMultimap.copyOf，连两参 new GameProfile(id,name) 用的 PropertyMap.EMPTY 也不可变）。
        // 旧版的 new GameProfile(id,name) + properties().put(...) 会抛 UnsupportedOperationException，
        // 致登录档案替换失败、玩家落回离线 UUID（背包/人物数据丢失）。
        // 故先填一个可变 Multimap，再用三参构造一次性建好不可变档案。
        Multimap<String, Property> properties = ArrayListMultimap.create();
        for (MojangPremiumVerifier.Property property : profile.properties()) {
            properties.put(property.name(),
                new Property(property.name(), property.value(), property.signature()));
        }
        return new GameProfile(profile.id(), profile.name(), new PropertyMap(properties));
    }

    private static String profileName(ServerLoginPacketListenerImpl handler) {
        GameProfile profile = ((ServerLoginPacketListenerImplAccessor) handler).zstdnet$getGameProfile();
        return profile != null ? profile.name() : null;
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
