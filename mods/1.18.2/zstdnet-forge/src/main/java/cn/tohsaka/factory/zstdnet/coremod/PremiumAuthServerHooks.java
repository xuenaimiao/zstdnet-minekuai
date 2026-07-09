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
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * 「登录阶段正版验证」——服务端 coremod 钩子（MC 1.20.1，旧版登录流程；与 Forge 1.18.2/1.19.2 同构）。
 * <p>
 * 由 {@code coremods/zstdnet_premium_auth.js} 注入三处：
 * <ol>
 *   <li>{@code tick()} 内 {@code NetworkHooks.tickNegotiation(...)} 调用点 → 重定向到 {@link #tickNegotiationGate}：
 *       在 negotiation 阶段（{@code PlayerNegotiationEvent} 触发<b>之前</b>）先发 nonce 查询并按住 negotiation，
 *       核验通过换成正版档案后才委派原版 negotiation。这样 LuckPerms 等「在 {@code PlayerNegotiationEvent}
 *       就按当时 UUID 预加载用户数据」的权限系统读到的已是正版 UUID，避免登录中途换 UUID 引发
 *       {@code placeNewPlayer}/{@code LoadFromFile} 崩溃（vanilla 报「Invalid player data」）。</li>
 *   <li>{@code handleAcceptedLogin()} 方法首部 → {@link #beforeFinalizeLogin}：兜底门控——若上一步重定向未命中
 *       （极老版本无 {@code PlayerNegotiationEvent}），仍在 {@code LoginSuccess} 之前发查询并门控（每 tick 重入
 *       轮询，原版 600-tick 慢登录超时照常生效）。正常情况下 negotiation 门控已先建好 pending，本方法只是放行。</li>
 *   <li>{@code handleCustomQueryPacket(ServerboundCustomQueryPacket)} 方法首部 → {@link #handleAnswer}：
 *       识别我方事务号的应答，后台 {@code hasJoined} 核验，成功替换登录档案、失败按策略处理。</li>
 * </ol>
 * 全程不触发原版加密握手 → 明文不变 → ZSTD 压缩照常（后端由自动检测保证以 {@code online-mode=false} 运行）。
 * <p>
 * nonce 走信道 {@code ResourceLocation} 路径（{@code zstdnet:auth/<hex>}）而非 payload：见
 * {@link PremiumAuthProtocol#channelPathWithServerId(String)}。
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

    /** Forge {@code NetworkHooks.tickNegotiation} 的反射句柄（延迟解析、缓存；避免对该 Forge API 的编译期硬依赖）。 */
    private static volatile Method tickNegotiationMethod;
    private static volatile boolean tickNegotiationResolved;

    private PremiumAuthServerHooks() {
    }

    /**
     * 注入在 {@code handleAcceptedLogin()} 首部。返回 {@code true} 放行（执行原版逻辑、发 LoginSuccess），
     * {@code false} 拦截本次（不放行，等待核验）。
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
                return true; // 拿不到用户名（异常情况）：不阻断登录
            }
            byte[] nonce = new byte[PremiumAuthProtocol.NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            pending = new Pending(nonce, username);
            PENDING.put(listener, pending);
            try {
                sendChallenge(connection, nonce);
            } catch (Throwable t) {
                // 发送失败：宽松放行，严格拒绝
                LOGGER.warn("[zstdnet-server] failed to send premium challenge: {}", t.toString());
                pending.phase = Phase.PROCEED;
                return finalizeFailure(listener, username, "could not send premium challenge");
            }
            return false;
        }
        return switch (pending.phase) {
            case AWAITING, VERIFYING -> false;
            case PROCEED -> true;
            case REJECT -> false; // 已断开
        };
    }

    /**
     * 重定向自 {@code tick()} 内的 {@code NetworkHooks.tickNegotiation(...)}——把「发挑战 + 门控」提前到
     * negotiation 阶段，使正版换档在 {@code PlayerNegotiationEvent} 触发之前完成。否则 LuckPerms 等在
     * {@code PlayerNegotiationEvent} 就按当时（离线）UUID 预加载用户数据，随后我方换成正版 UUID，会在
     * {@code placeNewPlayer} 的 {@code LoadFromFile} 里因取不到预加载数据而崩（vanilla 报「Invalid player data」）。
     * <p>
     * 返回值语义同原版：{@code true}=本次 negotiation 完成（可进入 {@code READY_TO_ACCEPT}），{@code false}=未完成
     * （保持 NEGOTIATING，下一 tick 再来）。按住期间只返回 {@code false} 而<b>不</b>调用原版 negotiation，故
     * {@code PlayerNegotiationEvent} 不会提前触发；核验完成（档案已换正版、或按策略回落离线）后才委派原版 negotiation。
     */
    public static boolean tickNegotiationGate(ServerLoginPacketListenerImpl listener,
                                              Connection connection, ServerPlayer player) {
        if (!PremiumAuthState.isEnabled() || connection == null || connection.isMemoryConnection()) {
            return realTickNegotiation(listener, connection, player); // 未启用/单机内存连接：不介入
        }
        Pending pending = PENDING.get(listener);
        if (pending == null) {
            String username = usernameOf(listener);
            if (username == null || username.isBlank()) {
                return realTickNegotiation(listener, connection, player); // 拿不到用户名：放行
            }
            byte[] nonce = new byte[PremiumAuthProtocol.NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            pending = new Pending(nonce, username);
            PENDING.put(listener, pending);
            try {
                sendChallenge(connection, nonce);
            } catch (Throwable t) {
                // 发送失败：宽松则回落离线（放行原版 negotiation），严格/保护则已在 finalizeFailure 断开
                LOGGER.warn("[zstdnet-server] failed to send premium challenge: {}", t.toString());
                pending.phase = Phase.PROCEED;
                boolean proceed = finalizeFailure(listener, username, "could not send premium challenge");
                return proceed && realTickNegotiation(listener, connection, player);
            }
            return false; // 首次：已发挑战，按住 negotiation（暂不触发 PlayerNegotiationEvent）
        }
        return switch (pending.phase) {
            case AWAITING, VERIFYING -> false;                                // 仍在验证，按住
            case PROCEED -> realTickNegotiation(listener, connection, player); // 换档完成→委派原版 negotiation（此时档案=正版/离线，一致）
            case REJECT -> false;                                             // 已断开
        };
    }

    /**
     * 注入在 {@code handleCustomQueryPacket(ServerboundCustomQueryPacket)} 首部。返回 {@code true} 表示这是我方
     * 应答、已消费（跳过原版处理），{@code false} 交还原版（Forge 自有 login 包）。
     */
    public static boolean handleAnswer(ServerLoginPacketListenerImpl listener, ServerboundCustomQueryPacket packet) {
        if (packet == null || packet.getTransactionId() != PremiumAuthProtocol.COREMOD_TRANSACTION_ID) {
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
        listener.disconnect(new TextComponent(rejection));
        return false;
    }

    private static void sendChallenge(Connection connection, byte[] nonce) {
        String serverId = PremiumAuthProtocol.serverIdFromNonce(nonce);
        ResourceLocation channel = new ResourceLocation(
            PremiumAuthProtocol.CHANNEL_NAMESPACE, PremiumAuthProtocol.channelPathWithServerId(serverId));
        FriendlyByteBuf empty = new FriendlyByteBuf(Unpooled.buffer());
        connection.send(new ClientboundCustomQueryPacket(PremiumAuthProtocol.COREMOD_TRANSACTION_ID, channel, empty));
    }

    private static GameProfile buildProfile(VerifiedProfile profile) {
        GameProfile gameProfile = new GameProfile(profile.id(), profile.name());
        for (MojangPremiumVerifier.Property property : profile.properties()) {
            gameProfile.getProperties().put(property.name(),
                new Property(property.name(), property.value(), property.signature()));
        }
        return gameProfile;
    }

    private static String usernameOf(ServerLoginPacketListenerImpl listener) {
        GameProfile profile = getProfile(listener);
        return profile != null ? profile.getName() : null;
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

    /** 委派给原版（Forge）的 {@code NetworkHooks.tickNegotiation}——即真正触发 {@code PlayerNegotiationEvent} 的地方。 */
    private static boolean realTickNegotiation(ServerLoginPacketListenerImpl listener,
                                               Connection connection, ServerPlayer player) {
        Method m = tickNegotiation();
        if (m == null) {
            // 兜底：无法回调原版 negotiation（正常不会发生——JS 能重定向即说明该方法存在）。
            // 宁可不卡登录也判为「negotiation 完成」放行，退化为修复前行为。
            return true;
        }
        try {
            Object r = m.invoke(null, listener, connection, player);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            LOGGER.warn("[zstdnet-server] NetworkHooks.tickNegotiation callback failed: {}", t.toString());
            return true;
        }
    }

    private static Method tickNegotiation() {
        if (tickNegotiationResolved) {
            return tickNegotiationMethod;
        }
        synchronized (PremiumAuthServerHooks.class) {
            if (tickNegotiationResolved) {
                return tickNegotiationMethod;
            }
            Method found = null;
            try {
                Class<?> hooks = Class.forName("net.minecraftforge.network.NetworkHooks");
                for (Method m : hooks.getMethods()) {
                    if (m.getName().equals("tickNegotiation") && m.getParameterCount() == 3
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                        found = m;
                        break;
                    }
                }
                if (found == null) {
                    LOGGER.warn("[zstdnet-server] NetworkHooks.tickNegotiation(3-arg) not found via reflection.");
                }
            } catch (Throwable t) {
                LOGGER.warn("[zstdnet-server] could not resolve NetworkHooks.tickNegotiation: {}", t.toString());
            }
            tickNegotiationMethod = found;
            tickNegotiationResolved = true;
            return found;
        }
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
