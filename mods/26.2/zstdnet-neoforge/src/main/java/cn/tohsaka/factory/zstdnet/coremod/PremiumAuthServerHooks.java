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

package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier;
import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.VerifiedProfile;
import cn.tohsaka.factory.zstdnet.auth.PremiumAuthProtocol;
import cn.tohsaka.factory.zstdnet.auth.PremiumAuthState;
import cn.tohsaka.factory.zstdnet.auth.PremiumVerificationService;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.DiscardedQueryPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「登录阶段正版验证」——服务端 coremod 钩子（MC 1.21.1，现代登录流程；与 NeoForge 26.1 同构）。
 * <p>
 * 由 {@code coremods/zstdnet_premium_auth.js} 注入两处：
 * <ol>
 *   <li>{@code verifyLoginAndFinishConnectionSetup(GameProfile)} 方法首部 → {@link #beforeFinalizeLogin}：
 *       在 {@code LoginSuccess} 之前发查询并门控。该方法由 {@code tick()} 在 {@code VERIFYING} 态每 tick 调用，
 *       早返回时不改状态，故天然轮询；原版 600-tick 慢登录超时仍生效。</li>
 *   <li>{@code handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket)} 方法首部 → {@link #handleAnswer}：
 *       识别我方事务号的应答，后台 {@code hasJoined} 核验，成功替换登录档案、失败按策略处理。</li>
 * </ol>
 * 现代 MC 会丢弃 login 查询的 payload 字节，故 nonce 走信道 {@code ResourceLocation} 路径（{@code zstdnet:auth/<hex>}）。
 * 全程不触发原版加密握手 → 明文不变 → ZSTD 压缩照常。
 */
public final class PremiumAuthServerHooks {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zstdnet-premium-auth");
        t.setDaemon(true);
        return t;
    });
    private static final Map<ServerLoginPacketListenerImpl, Pending> PENDING =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Field> PROFILE_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> CONNECTION_FIELD_CACHE = new ConcurrentHashMap<>();

    private PremiumAuthServerHooks() {
    }

    /**
     * 注入在 {@code verifyLoginAndFinishConnectionSetup(GameProfile)} 首部。返回 {@code true} 放行
     * （执行原版逻辑、发 LoginSuccess），{@code false} 拦截本次（不放行，等待核验）。
     */
    public static boolean beforeFinalizeLogin(ServerLoginPacketListenerImpl listener) {
        if (!PremiumAuthState.isEnabled()) {
            return true;
        }
        Connection connection = connectionOf(listener);
        if (connection == null || connection.isMemoryConnection()) {
            return true; // 单机/集成服无需验证
        }
        Pending pending = PENDING.get(listener);
        if (pending == null) {
            String username = usernameOf(listener);
            if (username == null || username.isBlank()) {
                return true;
            }
            byte[] nonce = new byte[PremiumAuthProtocol.NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            pending = new Pending(nonce, username);
            PENDING.put(listener, pending);
            try {
                sendChallenge(connection, nonce);
            } catch (Throwable t) {
                LOGGER.warn("[zstdnet-server] failed to send premium challenge: {}", t.toString());
                pending.phase = Phase.PROCEED;
                return finalizeFailure(listener, username, "could not send premium challenge");
            }
            return false;
        }
        return switch (pending.phase) {
            case AWAITING, VERIFYING -> false;
            case PROCEED -> true;
            case REJECT -> false;
        };
    }

    /**
     * 注入在 {@code handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket)} 首部。返回 {@code true} 表示
     * 这是我方应答、已消费（跳过原版断开），{@code false} 交还原版。
     */
    public static boolean handleAnswer(ServerLoginPacketListenerImpl listener, ServerboundCustomQueryAnswerPacket packet) {
        if (packet == null || packet.transactionId() != PremiumAuthProtocol.COREMOD_TRANSACTION_ID) {
            return false;
        }
        Pending pending = PENDING.get(listener);
        if (pending == null) {
            return false; // 从未对此连接发起查询 → 非我方应答，交还原版处理
        }
        if (pending.phase != Phase.AWAITING) {
            return true; // 重复/迟到应答：消费掉，避免原版把它当非法查询而断开
        }
        pending.phase = Phase.VERIFYING;
        String serverId = PremiumAuthProtocol.serverIdFromNonce(pending.nonce);
        String clientIp = PremiumAuthState.passRealIp() ? clientIp(listener) : null;
        EXECUTOR.execute(() -> {
            try {
                VerifiedProfile profile = PremiumVerificationService.verify(pending.username, serverId, clientIp);
                if (profile != null) {
                    setProfile(listener, buildProfile(profile));
                    LOGGER.info("[zstdnet-server] verified premium player {} -> {}", pending.username, profile.id());
                    pending.phase = Phase.PROCEED;
                } else {
                    finalizeFailure(listener, pending.username, "session server did not confirm " + pending.username);
                    if (pending.phase != Phase.REJECT) {
                        pending.phase = Phase.PROCEED;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[zstdnet-server] premium verification error for {}: {}", pending.username, t.toString());
                finalizeFailure(listener, pending.username, "verification error");
                if (pending.phase != Phase.REJECT) {
                    pending.phase = Phase.PROCEED;
                }
            }
        });
        return true;
    }

    /** 验证不通过：由共享策略决定——strict / 正版身份保护 → 断开（返回 false），其余宽松放行（返回 true）。 */
    private static boolean finalizeFailure(ServerLoginPacketListenerImpl listener, String username, String reason) {
        String rejection = PremiumVerificationService.rejectionMessage(username, reason);
        if (rejection == null) {
            return true;
        }
        Pending pending = PENDING.get(listener);
        if (pending != null) {
            pending.phase = Phase.REJECT;
        }
        listener.disconnect(Component.literal(rejection));
        return false;
    }

    private static void sendChallenge(Connection connection, byte[] nonce) {
        String serverId = PremiumAuthProtocol.serverIdFromNonce(nonce);
        Identifier channel = Identifier.fromNamespaceAndPath(
            PremiumAuthProtocol.CHANNEL_NAMESPACE, PremiumAuthProtocol.channelPathWithServerId(serverId));
        connection.send(new ClientboundCustomQueryPacket(
            PremiumAuthProtocol.COREMOD_TRANSACTION_ID, new DiscardedQueryPayload(channel)));
    }

    private static GameProfile buildProfile(VerifiedProfile profile) {
        // authlib 7.x（MC 26.1）的 GameProfile 是 record，其 PropertyMap 恒为不可变
        // （PropertyMap 构造即 ImmutableMultimap.copyOf）。旧版的 new GameProfile(id,name) + properties().put(...)
        // 会抛 UnsupportedOperationException，致登录档案替换失败、玩家落回离线 UUID（背包/人物数据丢失）。
        // 故先填一个可变 Multimap，再用三参构造一次性建好不可变档案。
        Multimap<String, Property> properties = ArrayListMultimap.create();
        for (MojangPremiumVerifier.Property property : profile.properties()) {
            properties.put(property.name(),
                new Property(property.name(), property.value(), property.signature()));
        }
        return new GameProfile(profile.id(), profile.name(), new PropertyMap(properties));
    }

    private static String usernameOf(ServerLoginPacketListenerImpl listener) {
        GameProfile profile = getProfile(listener);
        return profile != null ? profile.name() : null;
    }

    private static String clientIp(ServerLoginPacketListenerImpl listener) {
        Connection connection = connectionOf(listener);
        if (connection == null) {
            return null;
        }
        SocketAddress address = ServerRealIpHooks.getRemoteAddress(connection, connection.getRemoteAddress());
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }

    // ---- 反射访问登录档案 / 连接字段（按类型查找，跨混淆/官方映射稳健）----

    private static GameProfile getProfile(ServerLoginPacketListenerImpl listener) {
        Field field = profileField(listener.getClass());
        if (field == null) {
            return null;
        }
        try {
            return (GameProfile) field.get(listener);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void setProfile(ServerLoginPacketListenerImpl listener, GameProfile profile) {
        Field field = profileField(listener.getClass());
        if (field == null) {
            LOGGER.warn("[zstdnet-server] could not locate GameProfile field to swap premium profile.");
            return;
        }
        try {
            field.set(listener, profile);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[zstdnet-server] failed to set premium GameProfile: {}", e.toString());
        }
    }

    private static Connection connectionOf(ServerLoginPacketListenerImpl listener) {
        Field field = connectionField(listener.getClass());
        if (field == null) {
            return null;
        }
        try {
            return (Connection) field.get(listener);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Field profileField(Class<?> type) {
        return PROFILE_FIELD_CACHE.computeIfAbsent(type, t -> findFieldByType(t, GameProfile.class));
    }

    private static Field connectionField(Class<?> type) {
        return CONNECTION_FIELD_CACHE.computeIfAbsent(type, t -> findFieldByType(t, Connection.class));
    }

    private static Field findFieldByType(Class<?> start, Class<?> fieldType) {
        Class<?> type = start;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return field;
                    } catch (RuntimeException ignored) {
                        // 继续找
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private enum Phase {
        AWAITING,
        VERIFYING,
        PROCEED,
        REJECT
    }

    private static final class Pending {
        final byte[] nonce;
        final String username;
        volatile Phase phase = Phase.AWAITING;

        Pending(byte[] nonce, String username) {
            this.nonce = nonce;
            this.username = username;
        }
    }
}
