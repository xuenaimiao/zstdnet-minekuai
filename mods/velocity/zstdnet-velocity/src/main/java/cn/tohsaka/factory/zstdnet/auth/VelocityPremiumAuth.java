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

package cn.tohsaka.factory.zstdnet.auth;

import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Challenge;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.LegacyAnswer;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Outcome;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.PreLoginAction;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.StartupMode;
import cn.tohsaka.factory.zstdnet.network.ZstdnetConnections;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Velocity 端「登录阶段正版验证」（仿 TrueUUID，与 mod 变体同一套 {@code zstdnet:auth} 协议）。
 * <p>
 * <b>为什么需要</b>：Velocity 默认 {@code online-mode=true}，会对每个玩家做原版加密握手 → 之后整条流量是 AES 密文，
 * ZstdNet 的 ZSTD 压缩收益归零。本类只对<b>经 ZstdNet 代理进来</b>的连接改走带外验证：强制离线（不加密），
 * 登录阶段让客户端本机 {@code joinServer}、服务端 {@code hasJoined} 核验后换上真实正版 UUID/皮肤——全程明文。
 * 原版直连 Velocity 端口的玩家不受任何影响（仍是 Velocity 原有的在线认证）。
 *
 * <h2>事件流（Velocity 3.4 API；3.5.1 / 4.2.0 实现已逐字节核对，见下）</h2>
 * <ol>
 *   <li>{@link PreLoginEvent}（LATE 优先级，先看其他插件的决定）：对 ZstdNet 连接
 *       {@link PreLoginComponentResult#forceOfflineMode()}，并经 {@link LoginPhaseConnection#sendLoginPluginMessage}
 *       以同一 nonce 发两条查询（路径路线 + payload 路线，见 {@link VelocityPremiumLogic}）。
 *       Velocity 在 {@code PreLoginEvent} 期间只是把消息<b>排队</b>，事件结束后（{@code loginEventFired}）才一次性发出，
 *       并<b>等全部应答到齐</b>才进入离线的 {@code AuthSessionHandler}——所以到下一步时两条应答必然已到。</li>
 *   <li>{@link GameProfileRequestEvent}（FIRST 优先级，先于其他改档案的插件）：返回
 *       {@link EventTask#resumeWhenComplete}，在自有线程池上 {@code hasJoined}（不占 netty 事件循环），通过则
 *       {@code setGameProfile(正版档案)}。这是 Velocity 为「替换登录档案」设计的官方位置，早于
 *       {@code PermissionsSetupEvent} / {@code LoginEvent}，LuckPerms 等读到的即正版 UUID。</li>
 *   <li>{@link LoginEvent}（FIRST）：strict / 正版身份保护要求拒绝时在此 {@code denied(提示)}——Velocity 会在发
 *       {@code LoginSuccess} 之前断开并展示与 mod 变体相同的提示（{@code GameProfileRequestEvent} 本身没有拒绝能力）。</li>
 * </ol>
 * <b>已核对的实现事实</b>（velocity-3.5.1 与 4.2.0 的 {@code LoginInboundConnection}、{@code AuthSessionHandler}
 * 字节码一致；{@code InitialLoginSessionHandler} 仅在线认证的 HTTP 部分不同）：消息 id 由 Velocity 自增分配
 * （无法指定 {@link PremiumAuthProtocol#COREMOD_TRANSACTION_ID}，但所有已发布客户端只认信道、原样回显事务号）；
 * 应答 {@code success=false} 时回调收到 {@code null}，否则收到负载；{@code PreLoginEvent} 的结果对象在发消息前
 * 就已被捕获，应答到达后无法再改在线/离线，故「验证不通过时退回在线认证」在 Velocity API 下做不到，
 * 宽松模式只能按 mod 变体的语义回落离线身份（{@code premium_uuid_guard} 兜住曾验证过的玩家）。
 */
public final class VelocityPremiumAuth {

    /**
     * = {@code PostOrder.LATE}（Velocity 内部映射为 -16384）：让 FastLogin / LimboAuth 等先表态，我们只在「无人表态」时接管。
     * （Velocity 对非 0 的 {@code priority} 直接采用，否则才按 {@code order} 映射：FIRST=32766 … LAST=-32767。）
     */
    private static final short PRE_LOGIN_PRIORITY = -16384;
    /** 比 {@code PostOrder.FIRST}（32766）还早：先确立认证身份，其他插件（皮肤等）在其基础上再改。 */
    private static final short PROFILE_PRIORITY = Short.MAX_VALUE;
    /** 同上：尽早标记拒绝，后续监听器（LuckPerms 等）据 {@code getResult()} 即可跳过。 */
    private static final short LOGIN_PRIORITY = Short.MAX_VALUE;

    /** 应答等待上限（兜底；按 Velocity 流程到 GameProfileRequestEvent 时应答必已到齐）。 */
    private static final long ANSWER_TIMEOUT_SECONDS = 30L;
    /** 待拒绝记录的存活上限（玩家在 GameProfileRequestEvent 与 LoginEvent 之间断开时靠它清理）。 */
    private static final long REJECTION_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 每条被接管连接的验证会话；键为 Velocity 的 {@code LoginInboundConnection}（按身份比较，弱引用防泄漏）。 */
    private static final Map<InboundConnection, VelocityPremiumSession> SESSIONS =
        Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * 待在 {@link LoginEvent} 拒绝的连接。键为远端地址：{@code LoginInboundConnection} 与随后的 {@code ConnectedPlayer}
     * 读的是同一个 {@code MinecraftConnection#getRemoteAddress()}，故两处取到的是同一个值。
     */
    private static final Map<SocketAddress, Rejection> REJECTIONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean OLD_PROTOCOL_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean OTHER_PLUGIN_LOGGED = new AtomicBoolean();

    private static volatile Logger logger;
    private static volatile ExecutorService executor;
    private static volatile Listener listener;
    private static volatile Object pluginInstance;
    private static volatile ProxyServer proxy;

    private VelocityPremiumAuth() {
    }

    /**
     * {@code ProxyInitializeEvent} 时调用：解析配置、写入 {@link PremiumAuthState}、按需注册登录事件监听。
     * {@code premium_*} 与 mod 变体一样只在启动时读取（改动需重启 Velocity）。
     */
    public static synchronized void init(Object plugin, ProxyServer server, Logger log) {
        logger = log;
        boolean velocityOnline = server.getConfiguration().isOnlineMode();
        StartupMode mode = VelocityPremiumLogic.startupMode(
            ServerProxyConfigFile.readPremiumVerification(), velocityOnline);
        if (mode != StartupMode.ENABLED) {
            PremiumAuthState.disable();
            if (mode == StartupMode.DISABLED_ENCRYPTED) {
                log.warn("[zstdnet] Velocity online-mode=true but premium_verification=off -> players coming through"
                    + " ZstdNet get Velocity's vanilla encryption and zstd compression yields almost nothing for them."
                    + " Set premium_verification=auto (or on) in {} and restart Velocity to keep both premium identity"
                    + " and compression.", ServerProxyConfigFile.path());
            }
            return;
        }

        boolean strict = PremiumAuthState.resolveStrict(ServerProxyConfigFile.readPremiumVerificationMode());
        String sessionConfig = VelocityPremiumLogic.effectiveSessionServerConfig(
            ServerProxyConfigFile.readPremiumSessionServer(),
            System.getProperty(VelocityPremiumLogic.VELOCITY_SESSION_PROPERTY));
        PremiumAuthState.configure(
            true,
            strict,
            sessionConfig,
            ServerProxyConfigFile.readPremiumPassRealIp(),
            ServerProxyConfigFile.readPremiumUuidGuard());

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "zstdnet-premium-auth");
            t.setDaemon(true);
            return t;
        });
        pluginInstance = plugin;
        proxy = server;
        listener = new Listener();
        server.getEventManager().register(plugin, listener);

        log.info("[zstdnet] built-in premium verification enabled (mode={}, uuid_guard={}, session_servers={}):"
                + " players coming through ZstdNet are forced to offline-mode (no encryption) and verified via"
                + " zstdnet:auth, so zstd compression stays effective; direct players keep Velocity's own {}"
                + " authentication.",
            strict ? "strict" : "lenient", PremiumAuthState.uuidGuardEnabled(), PremiumAuthState.sessionBaseUrls(),
            velocityOnline ? "online-mode" : "offline-mode");
    }

    /** {@code ProxyShutdownEvent} 时调用：注销监听、停线程池、清状态。 */
    public static synchronized void shutdown() {
        Listener current = listener;
        ProxyServer server = proxy;
        if (current != null && server != null) {
            try {
                server.getEventManager().unregisterListener(pluginInstance, current);
            } catch (RuntimeException ignored) {
                // 代理关停中：注销失败无关紧要
            }
        }
        listener = null;
        proxy = null;
        pluginInstance = null;
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        SESSIONS.clear();
        REJECTIONS.clear();
        PremiumAuthState.disable();
    }

    /**
     * 登录事件监听（Velocity 以反射 + LambdaMetafactory 调用 {@code @Subscribe} 方法，故类与方法保持 public）。
     */
    public static final class Listener {

        Listener() {
        }

        @Subscribe(priority = PRE_LOGIN_PRIORITY)
        public void onPreLogin(PreLoginEvent event) {
            if (!PremiumAuthState.isEnabled()) {
                return;
            }
            InboundConnection connection = event.getConnection();
            InetSocketAddress remote = connection.getRemoteAddress();
            ZstdnetConnections.Info info = ZstdnetConnections.lookup(remote);
            boolean injectorActive = ZstdnetConnections.injectorActive();
            String rawHost = connection.getRawVirtualHost().orElse(null);
            if (!VelocityPremiumLogic.isZstdnetConnection(
                injectorActive, info != null, remote != null ? remote.getAddress() : null, rawHost)) {
                return;
            }
            if (info == null && !injectorActive && FALLBACK_LOGGED.compareAndSet(false, true)) {
                logger.info("[zstdnet] pipeline injector is not active; recognising ZstdNet connections by the loopback"
                    + " source + zstdnet-real-ip handshake marker instead.");
            }

            PreLoginComponentResult result = event.getResult();
            PreLoginAction action = VelocityPremiumLogic.preLoginAction(
                connection.getProtocolVersion().getProtocol(),
                result.isAllowed(),
                result.isOnlineModeAllowed(),
                result.isForceOfflineMode(),
                connection instanceof LoginPhaseConnection);
            String username = event.getUsername();
            switch (action) {
                case INTERCEPT:
                    intercept(event, (LoginPhaseConnection) connection, username, info, rawHost);
                    return;
                case SKIP_PROTOCOL_UNSUPPORTED:
                    // 每次启动只以 INFO 说明一次，之后降为 DEBUG，免得刷屏。
                    logOnceThenDebug(OLD_PROTOCOL_LOGGED, "[zstdnet] {} connected through ZstdNet with MC protocol {}"
                            + " (older than 1.18.2): no ZstdNet client for that version supports built-in premium"
                            + " verification, so Velocity's own authentication is kept for such players.",
                        username, connection.getProtocolVersion().getProtocol());
                    return;
                case SKIP_OTHER_PLUGIN_DECIDED:
                    logOnceThenDebug(OTHER_PLUGIN_LOGGED, "[zstdnet] another plugin already chose {} for {}; leaving"
                            + " such decisions in place (no zstdnet:auth premium verification for those logins; with"
                            + " online-mode they are encrypted and zstd cannot compress them).",
                        result.isOnlineModeAllowed() ? "online-mode" : "offline-mode", username);
                    return;
                case SKIP_DENIED:
                case SKIP_NOT_LOGIN_PHASE:
                default:
                    return;
            }
        }

        /**
         * 返回 {@link EventTask}：Velocity 会等它完成才继续处理后续监听器与登录流程；{@code hasJoined}
         * 在自有线程池上跑，不阻塞事件线程也不阻塞 netty 事件循环。
         */
        @Subscribe(priority = PROFILE_PRIORITY)
        public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
            VelocityPremiumSession session = SESSIONS.remove(event.getConnection());
            if (session == null) {
                return null;
            }
            if (event.isOnlineMode()) {
                // 我们之后的插件把结果改回了在线模式：Velocity 已走原版认证，身份可信，无需再验。
                logger.info("[zstdnet] {} was authenticated by Velocity's online-mode after all; skipping zstdnet:auth"
                    + " verification.", session.username());
                return null;
            }
            ExecutorService pool = executor;
            if (pool == null || pool.isShutdown()) {
                return null;
            }
            SocketAddress address = event.getConnection().getRemoteAddress();
            GameProfile original = event.getGameProfile();
            CompletableFuture<Void> task = session.answered()
                .orTimeout(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((ignored, error) -> error == null)
                .thenApplyAsync(answered -> VelocityPremiumLogic.resolve(
                    session.username(),
                    session.serverId(),
                    session.clientIp(),
                    answered ? session.legacyAnswer() : LegacyAnswer.PENDING,
                    answered,
                    PremiumVerificationService::verify,
                    PremiumVerificationService::rejectionMessage), pool)
                .thenAccept(outcome -> apply(event, original, address, session, outcome))
                .exceptionally(t -> {
                    onUnexpectedFailure(address, session, t);
                    return null;
                });
            return EventTask.resumeWhenComplete(task);
        }

        @Subscribe(priority = LOGIN_PRIORITY)
        public void onLogin(LoginEvent event) {
            Player player = event.getPlayer();
            Rejection rejection = REJECTIONS.remove(normalize(player.getRemoteAddress()));
            if (rejection == null || !rejection.username().equalsIgnoreCase(player.getUsername())) {
                return;
            }
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text(rejection.message())));
        }
    }

    // ---- 内部 ----

    private static void intercept(
        PreLoginEvent event,
        LoginPhaseConnection connection,
        String username,
        ZstdnetConnections.Info info,
        String rawHost
    ) {
        byte[] nonce = new byte[PremiumAuthProtocol.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        String clientIp = PremiumAuthState.passRealIp()
            ? VelocityPremiumLogic.clientIpForVerification(info != null ? info.realIp() : null, rawHost)
            : null;
        List<Challenge> challenges = VelocityPremiumLogic.challenges(nonce);
        VelocityPremiumSession session = new VelocityPremiumSession(username, nonce, clientIp, challenges.size());
        SESSIONS.put(connection, session);
        try {
            for (Challenge challenge : challenges) {
                connection.sendLoginPluginMessage(
                    MinecraftChannelIdentifier.create(challenge.namespace(), challenge.name()),
                    challenge.content(),
                    body -> session.onAnswer(challenge.route(), body));
            }
        } catch (RuntimeException e) {
            // 还没强制离线：直接交还 Velocity 原有认证（在线模式下即原版加密认证），身份不丢——比 mod 变体
            // 「发送失败即按策略处置」更保守。已排队的查询照常应答，只是无人消费。
            SESSIONS.remove(connection);
            logger.warn("[zstdnet] failed to send premium challenge to {}; keeping Velocity's own authentication: {}",
                username, e.toString());
            return;
        }
        event.setResult(PreLoginComponentResult.forceOfflineMode());
    }

    private static void apply(
        GameProfileRequestEvent event,
        GameProfile original,
        SocketAddress address,
        VelocityPremiumSession session,
        Outcome outcome
    ) {
        switch (outcome.kind()) {
            case VERIFIED:
                event.setGameProfile(VelocityPremiumLogic.toVelocityProfile(outcome.profile(), original));
                logger.info("[zstdnet] verified premium player {} -> {}", session.username(), outcome.profile().id());
                return;
            case REJECT:
                // 保留离线档案，到 LoginEvent 再拒绝（共享策略已打过拒绝日志）。
                logError(session, outcome, false);
                putRejection(address, session.username(), outcome.message());
                return;
            case OFFLINE:
            default:
                // 宽松放行：保留 Velocity 生成的离线档案（共享策略已打过日志）。
                logError(session, outcome, true);
        }
    }

    private static void logError(VelocityPremiumSession session, Outcome outcome, boolean fallingBackOffline) {
        if (outcome.error() == null) {
            return;
        }
        if (fallingBackOffline) {
            logger.error("[zstdnet] premium verification failed for {} (falling back to offline identity; player data"
                + " may differ): {}", session.username(), outcome.error().toString(), outcome.error());
        } else {
            logger.error("[zstdnet] premium verification failed for {}: {}",
                session.username(), outcome.error().toString(), outcome.error());
        }
    }

    private static void logOnceThenDebug(AtomicBoolean flag, String format, Object... args) {
        if (flag.compareAndSet(false, true)) {
            logger.info(format, args);
        } else {
            logger.debug(format, args);
        }
    }

    /** 异步链意外失败（理论不可达）：按共享策略处置，strict / 保护命中时仍然拒绝，绝不因异常放行。 */
    private static void onUnexpectedFailure(SocketAddress address, VelocityPremiumSession session, Throwable t) {
        logger.error("[zstdnet] premium verification failed for {}: {}", session.username(), t.toString(), t);
        try {
            String message = PremiumVerificationService.rejectionMessage(session.username(), "verification error: " + t);
            if (message != null) {
                putRejection(address, session.username(), message);
            }
        } catch (RuntimeException e) {
            logger.error("[zstdnet] premium rejection policy failed for {}: {}", session.username(), e.toString());
        }
    }

    private static void putRejection(SocketAddress address, String username, String message) {
        long now = System.nanoTime();
        REJECTIONS.values().removeIf(r -> now - r.createdNanos() > REJECTION_TTL_NANOS);
        SocketAddress key = normalize(address);
        if (key != null) {
            REJECTIONS.put(key, new Rejection(username, message, now));
        }
    }

    private static SocketAddress normalize(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.isUnresolved()) {
            return new InetSocketAddress(inet.getHostString(), inet.getPort());
        }
        return address;
    }

    /** 待拒绝记录。 */
    private record Rejection(String username, String message, long createdNanos) {
    }
}
