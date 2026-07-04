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
 * 「登录阶段正版验证」（MC 1.20.1 Fabric 网络 API）。见 {@link PremiumAuthProtocol} 的机制说明。
 * <p>
 * 服务端：登录协商一开始（{@code QUERY_START}）就在信道 {@code zstdnet:auth} 上发一条带 nonce 的查询；
 * 收到客户端应答后用 {@code LoginSynchronizer.waitFor} 挂起登录完成、后台调 {@code hasJoined} 核验，
 * 通过则把登录档案替换为真实正版档案，再放行 {@code handleAcceptedLogin}。<br>
 * 客户端：收到查询后本地 {@code joinServer}（access token 不出客户端）并应答。
 * <p>
 * 安全模型与 TrueUUID 一致：serverId 即服务端一次性 nonce，绑定本次登录、单用，足以防重放与冒名；
 * 因不复用原版加密握手，理论上不抵御「用户主动连接到的恶意服务端」发起的会话转发——这是任何
 * 「离线后端 + 登录阶段校验」方案的固有取舍，且本功能为可选 opt-in。
 */
public final class PremiumAuthSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CHANNEL =
        new ResourceLocation(PremiumAuthProtocol.CHANNEL_NAMESPACE, PremiumAuthProtocol.CHANNEL_PATH);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zstdnet-premium-auth");
        t.setDaemon(true);
        return t;
    });
    /** 每个登录连接的待核验 nonce（弱引用，连接结束自动清理）。 */
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
                return; // 单机/集成服无需验证
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
                        client.getMinecraftSessionService().joinServer(user.getGameProfile(), user.getAccessToken(), serverId);
                        authenticated = true;
                        source = "mojang";
                    } catch (Throwable t) {
                        // 离线启动器 / 无效会话：保持未验证，由服务端按策略处理（宽松=离线进服）。
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
