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
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.network.protocol.login.custom.DiscardedQueryPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 「登录阶段正版验证」——Fabric 服务端/客户端钩子（MC 26.1 现代登录流程；非混淆）。
 * <p>
 * <b>为什么不用 fabric-api 的 {@code ServerLoginConnectionEvents.QUERY_START}</b>：LuckPerms 等权限系统在
 * {@code QUERY_START} 回调里<b>同步</b>读取当时的登录 UUID 预加载用户数据，而 fabric-api 恰在 {@code QUERY_START}
 * 才发查询——正版换档（需一次客户端往返）必然晚于这次同步读取，于是 LuckPerms 按离线 UUID 预加载、玩家却以正版
 * UUID 进服，{@code ServerPlayConnectionEvents.JOIN} 处 {@code getIfLoaded(正版)} 取不到而干净断开
 * （{@code LOADING_STATE_ERROR}）。故改用 mixin，在 {@code QUERY_START} 触发<b>之前</b>完成换档：
 * <ul>
 *   <li>{@link #gateProceed}：注入 {@code tick()} 首部（{@code PremiumAuthServerLoginMixin}）。首个 tick 发出 nonce
 *       查询并按住 {@code tick()}（cancel），使其永不推进到 fabric-api 触发 {@code QUERY_START} 的
 *       {@code tickVerify} 调用点；核验通过换成正版档案后才放行——此后 {@code QUERY_START} 才触发，LuckPerms
 *       读到的已是正版 UUID。因 cancel 掉 {@code tick()} 会一并跳过原版 600-tick 慢登录超时，故自带一个门控超时。</li>
 *   <li>{@link #serverHandleAnswer}：注入 {@code handleCustomQueryPacket} 首部（同 mixin，优先级高于 fabric-api）。
 *       识别我方事务号的应答，后台 {@code hasJoined} 核验、成功替换登录档案。</li>
 *   <li>{@link #clientHandleQuery}：注入客户端 {@code ClientHandshakePacketListenerImpl#handleCustomQuery} 首部
 *       （{@code PremiumAuthClientQueryMixin}）。本机 {@code joinServer}（access token 不出客户端）后回一条空应答。</li>
 * </ul>
 * 26.1（非混淆）差异：{@code ResourceLocation} 改名 {@code Identifier}；authlib 7.x 的 {@code GameProfile} 是
 * record（{@code PropertyMap} 恒不可变，须三参构造）；会话服务经 {@code Minecraft.services().sessionService()}。
 * 协议同 Forge/NeoForge coremod：nonce 走信道路径 {@code zstdnet:auth/<hex>}。全程明文 → ZSTD 压缩照常。
 */
public final class PremiumAuthSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(daemon("zstdnet-premium-auth"));
    private static final ExecutorService CLIENT_EXECUTOR =
        Executors.newCachedThreadPool(daemon("zstdnet-premium-auth-client"));
    /** 每个登录连接的待核验状态（弱引用，连接结束自动清理）。 */
    private static final Map<ServerLoginPacketListenerImpl, Pending> PENDING =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** 门控自超时（tick）：cancel 掉 {@code tick()} 会跳过原版 600-tick 超时，故自带一个等值的兜底。 */
    private static final int GATE_TIMEOUT_TICKS = 600;

    private PremiumAuthSync() {
    }

    /** 登录阶段验证已改为 mixin 驱动，入口不再注册 fabric-api 事件；保留空实现以兼容 {@code Zstdnet} 的调用。 */
    public static void init() {
    }

    /** 同上：客户端不再注册 fabric-api 接收器（改由 {@code PremiumAuthClientQueryMixin} 驱动）。 */
    public static void initClient() {
    }

    // ---- 服务端：tick 门控（在 QUERY_START/tickVerify 触发之前按住，换档完成后才放行）----

    /**
     * 注入在 {@code ServerLoginPacketListenerImpl#tick()} 首部。返回 {@code true} 放行（不 cancel，
     * 让 fabric-api 照常触发 {@code QUERY_START} 并推进登录），{@code false} 按住（mixin 会 cancel 本次 tick）。
     */
    public static boolean gateProceed(ServerLoginPacketListenerImpl listener) {
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
                // 发送失败：宽松则回落离线（放行），严格/保护则已在 finalizeFailure 断开
                LOGGER.warn("[zstdnet-server] failed to send premium challenge: {}", t.toString());
                pending.phase = Phase.PROCEED;
                return finalizeFailure(listener, username, "could not send premium challenge");
            }
            return false; // 首次：已发挑战，按住（QUERY_START 暂不触发）
        }
        switch (pending.phase) {
            case PROCEED:
                return true; // 换档完成（正版/回落离线，UUID 全程一致）→ 放行，QUERY_START 现在才触发
            case REJECT:
                return false; // 已断开
            case AWAITING:
            case VERIFYING:
            default:
                if (++pending.gateTicks > GATE_TIMEOUT_TICKS) {
                    LOGGER.warn("[zstdnet-server] premium challenge timed out for {}", pending.username);
                    boolean proceed = finalizeFailure(listener, pending.username, "premium challenge timed out");
                    if (pending.phase != Phase.REJECT) {
                        pending.phase = Phase.PROCEED;
                    }
                    return proceed;
                }
                return false; // 仍在验证，按住
        }
    }

    // ---- 服务端：应答拦截（handleCustomQueryPacket 首部，mixin 传入事务号）----

    /**
     * 注入在 {@code handleCustomQueryPacket} 首部。返回 {@code true} 表示这是我方应答、已消费
     * （mixin 会 cancel，跳过 fabric-api/原版处理），{@code false} 交还原版。
     */
    public static boolean serverHandleAnswer(ServerLoginPacketListenerImpl listener, int transactionId) {
        if (transactionId != PremiumAuthProtocol.COREMOD_TRANSACTION_ID) {
            return false;
        }
        Pending pending = PENDING.get(listener);
        if (pending == null) {
            return false; // 从未对此连接发起查询 → 非我方应答，交还原版
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
                LOGGER.error("[zstdnet-server] premium verification failed for {} (falling back to offline identity; player data may differ): {}",
                    pending.username, t.toString(), t);
                finalizeFailure(listener, pending.username, "verification error: " + t);
                if (pending.phase != Phase.REJECT) {
                    pending.phase = Phase.PROCEED;
                }
            }
        });
        return true;
    }

    // ---- 客户端：查询处理（ClientHandshakePacketListenerImpl#handleCustomQuery 首部）----

    /**
     * 注入在客户端 {@code handleCustomQuery} 首部。返回 {@code true} 表示我方查询、已接管（mixin 会 cancel），
     * {@code false} 交还原版。
     */
    public static boolean clientHandleQuery(Connection connection, ClientboundCustomQueryPacket packet) {
        if (packet == null) {
            return false;
        }
        CustomQueryPayload payload = packet.payload();
        Identifier id = payload != null ? payload.id() : null;
        if (id == null || !PremiumAuthProtocol.CHANNEL_NAMESPACE.equals(id.getNamespace())) {
            return false;
        }
        String serverId = PremiumAuthProtocol.serverIdFromChannelPath(id.getPath());
        if (serverId == null) {
            return false;
        }
        int transactionId = packet.transactionId();
        CLIENT_EXECUTOR.execute(() -> {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                User user = minecraft.getUser();
                minecraft.services().sessionService().joinServer(
                    user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Throwable t) {
                // 离线启动器 / 无效会话：保持未验证，由服务端按策略处理（宽松=离线进服）。
                LOGGER.debug("[zstdnet-client] premium joinServer failed (offline session?): {}", t.toString());
            } finally {
                connection.send(new ServerboundCustomQueryAnswerPacket(transactionId, null));
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
        // authlib 7.x（MC 26.1）的 GameProfile 是 record，其 PropertyMap 恒为不可变（构造即 ImmutableMultimap.copyOf）。
        // 旧版的 new GameProfile(id,name) + properties().put(...) 会抛 UnsupportedOperationException，
        // 致登录档案替换失败、玩家落回离线 UUID（背包/人物数据丢失）。故先填一个可变 Multimap，再三参一次性建好。
        Multimap<String, Property> properties = ArrayListMultimap.create();
        for (MojangPremiumVerifier.Property property : profile.properties()) {
            properties.put(property.name(),
                new Property(property.name(), property.value(), property.signature()));
        }
        return new GameProfile(profile.id(), profile.name(), new PropertyMap(properties));
    }

    private static String usernameOf(ServerLoginPacketListenerImpl listener) {
        GameProfile profile = ((ServerLoginPacketListenerImplAccessor) listener).zstdnet$getGameProfile();
        return profile != null ? profile.name() : null;
    }

    private static void setProfile(ServerLoginPacketListenerImpl listener, GameProfile profile) {
        ((ServerLoginPacketListenerImplAccessor) listener).zstdnet$setGameProfile(profile);
    }

    private static Connection connectionOf(ServerLoginPacketListenerImpl listener) {
        return ((ServerLoginPacketListenerImplAccessor) listener).zstdnet$getConnection();
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

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
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
        int gateTicks;

        Pending(byte[] nonce, String username) {
            this.nonce = nonce;
            this.username = username;
        }
    }
}
