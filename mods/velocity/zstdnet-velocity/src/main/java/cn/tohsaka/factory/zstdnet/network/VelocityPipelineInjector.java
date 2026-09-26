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

import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Velocity 端的入站管线注入：对经 ZstdNet 服务端代理进来的玩家做「真实 IP 还原」与「关掉 Velocity 自身的 zlib」。
 *
 * <p><b>要解决什么</b>：本插件的 zstd 代理是独立监听端口 → 经 {@code 127.0.0.1} 转发到 Velocity 自己的 bind 端口，
 * 所以 Velocity 看到的远端地址恒为回环地址（进服日志、{@code login-ratelimit}、按 IP 的插件全都失效，
 * 同一时刻多名 ZstdNet 玩家还会被按「同一个 IP」限流）；同时 Velocity 会对发给客户端的包做 zlib 压缩，
 * 压过的数据 ZSTD 基本压不动。</p>
 *
 * <p><b>怎么做</b>：{@code ServerProxyRuntime} 转发登录握手时会在 host 末尾追加 {@code \0zstdnet-real-ip=<base64(ip)>}。
 * 本类在 {@code ProxyInitializeEvent} 时包装 Velocity 的子通道初始化器
 * （{@code VelocityServer#cm} → {@code ConnectionManager#getServerChannelInitializer()} →
 * {@code ServerChannelInitializerHolder#get/set}，与 ViaVersion 同一入口），为每条新连接在管线最前挂一个
 * {@link VelocityHandshakeSniffer}：只对本机来的连接读首帧、剥掉标记、用 netty {@code HAProxyMessage} 把真实地址交给
 * Velocity、登记进 {@link ZstdnetConnections}，并挂 {@link VelocityCompressionTuner} 抬高该连接的出站压缩阈值。</p>
 *
 * <p><b>时序</b>（Velocity 3.5.1 与 4.2.0 字节码核对一致）：{@code VelocityServer#start()} 里
 * {@code eventManager.fire(new ProxyInitializeEvent()).join()} 之后才 {@code cm.bind(...)}，而
 * {@code ConnectionManager#bind} 在绑定时才 {@code serverChannelInitializer.get()} 取初始化器——所以在该事件里
 * {@code set} 的包装器一定生效；{@code /velocity reload} 改了 bind 重新绑定时同样取到的是包装器。</p>
 *
 * <p>整个功能尽力而为：找不到内部结构 / 反射被挡下只告警一次，连接照常（只是看到回环地址、保留 zlib）。</p>
 */
public final class VelocityPipelineInjector {

    /** 真实 IP 开关的缓存时长：配置文件每次读都是磁盘 IO，不能在事件循环上逐连接读。 */
    private static final long REAL_IP_SETTING_TTL_NANOS = 5_000_000_000L;

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FIRST_RESTORE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_COMPRESSION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_ENCRYPTED_LOGGED = new AtomicBoolean();

    private static volatile Logger logger;
    private static volatile boolean active;
    private static volatile boolean injected;

    private VelocityPipelineInjector() {
    }

    /**
     * {@code ProxyInitializeEvent}（{@code PostOrder.FIRST}）时调用：包装 Velocity 的子通道初始化器。
     * 必须在 Velocity 绑定监听端口之前——见类注释里的时序说明。
     */
    public static void init(ProxyServer server, Logger log) {
        logger = log;
        if (injected) {
            active = true;
            ZstdnetConnections.markInjectorActive(true);
            return;
        }
        try {
            VelocityPeerTrust.refreshNow();
            BooleanSupplier realIp = new CachedFlag(ServerProxyConfigFile::readRealIpForwarding, REAL_IP_SETTING_TTL_NANOS);
            VelocityHandshakeSniffer.Settings settings = new VelocityHandshakeSniffer.Settings(
                Channel::remoteAddress,
                VelocityPeerTrust::isTrusted,
                realIp,
                () -> server.getConfiguration().getCompressionThreshold(),
                VelocityCompressionTuner.ZSTD_COMPRESSION_THRESHOLD,
                new LogReporter());

            Object holder = locateInitializerHolder(server);
            Object current = holder.getClass().getMethod("get").invoke(holder);
            if (!(current instanceof ChannelInitializer)) {
                throw new IllegalStateException("unexpected server channel initializer: " + current);
            }
            @SuppressWarnings("unchecked")
            ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) current;
            VelocityChannelInitializer wrapper = VelocityChannelInitializer.wrap(
                original, () -> new VelocityHandshakeSniffer(settings), () -> active);

            // 用 MethodHandle 调 set：Velocity 在 set 里会打印一条「initializer has been replaced by <调用栈帧>」的 WARN，
            // MethodHandle 的中间帧默认隐藏，这样那条 WARN 指向的是本类，服主一眼能看出是谁换的。
            MethodHandle set = MethodHandles.publicLookup().findVirtual(holder.getClass(), "set",
                MethodType.methodType(void.class, ChannelInitializer.class));
            set.invoke(holder, wrapper);

            injected = true;
            active = true;
            ZstdnetConnections.markInjectorActive(true);

            boolean restore = realIp.getAsBoolean();
            int velocityThreshold = safeThreshold(server);
            log.info("[zstdnet] hooked Velocity's connection pipeline (the 'server channel initializer has been replaced'"
                + " warning above is expected): ZstdNet players {}; Velocity's zlib compression is {} for them."
                + " Direct vanilla connections are untouched.",
                restore ? "show their real address (real_ip_forwarding=true)"
                    : "show as a loopback address (real_ip_forwarding=false; Velocity's login-ratelimit then counts them as one IP)",
                velocityThreshold < 0 ? "already off (compression-threshold=-1)"
                    : "lifted (threshold " + velocityThreshold + " -> " + VelocityCompressionTuner.ZSTD_COMPRESSION_THRESHOLD
                        + ") so ZSTD can do the work");
        } catch (Throwable t) {
            Throwable cause = t instanceof ExceptionInInitializerError && t.getCause() != null ? t.getCause() : t;
            ZstdnetConnections.markInjectorActive(false);
            log.warn("[zstdnet] could not hook Velocity's connection pipeline ({}). The zstd proxy still works, but players"
                + " coming through it will show as 127.0.0.1 and keep Velocity's zlib compression.", cause.toString());
        }
    }

    /**
     * {@code ProxyShutdownEvent} 时调用：停止给新连接挂处理器、清空登记表。
     * 包装器本身留在 Velocity 里（监听通道已绑定，换回去也不会生效），停用后它只透明地调用被包装者。
     */
    public static void shutdown() {
        active = false;
        ZstdnetConnections.markInjectorActive(false);
        ZstdnetConnections.clear();
        FIRST_RESTORE_LOGGED.set(false);
        FIRST_COMPRESSION_LOGGED.set(false);
        FIRST_ENCRYPTED_LOGGED.set(false);
    }

    // ------------------------------------------------------------------
    // 定位 ServerChannelInitializerHolder
    // ------------------------------------------------------------------

    /** {@code VelocityServer#cm}（私有字段，无公开 getter）→ {@code ConnectionManager#getServerChannelInitializer()}。 */
    private static Object locateInitializerHolder(ProxyServer server) throws ReflectiveOperationException {
        Object cm = readConnectionManager(server);
        if (cm == null) {
            throw new NoSuchFieldException(server.getClass().getName() + ".cm (ConnectionManager)");
        }
        try {
            return cm.getClass().getMethod("getServerChannelInitializer").invoke(cm);
        } catch (NoSuchMethodException e) {
            Field field = cm.getClass().getField("serverChannelInitializer");
            return field.get(cm);
        }
    }

    private static Object readConnectionManager(Object server) throws IllegalAccessException {
        Field byType = null;
        for (Class<?> type = server.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getName().equals("cm")) {
                    field.setAccessible(true);
                    return field.get(server);
                }
                if (byType == null && field.getType().getSimpleName().equals("ConnectionManager")) {
                    byType = field;
                }
            }
        }
        if (byType != null) {
            byType.setAccessible(true);
            return byType.get(server);
        }
        return null;
    }

    private static int safeThreshold(ProxyServer server) {
        try {
            return server.getConfiguration().getCompressionThreshold();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    /** 同一类问题只告警一次（按 key 区分），避免每条连接刷屏。 */
    static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            Logger log = logger;
            if (log != null) {
                log.warn("[zstdnet] velocity pipeline: {}", message);
            }
        }
    }

    private static final class LogReporter implements VelocityHandshakeSniffer.Reporter {
        @Override
        public void restored(SocketAddress backend, SocketAddress forwarded, VelocityHandshakeSniffer.AddressOutcome outcome) {
            Logger log = logger;
            if (log == null) {
                return;
            }
            if (FIRST_RESTORE_LOGGED.compareAndSet(false, true)) {
                log.info("[zstdnet] real IP restore working: {} -> {} ({}; logged once, later connections are handled silently).",
                    backend, forwarded, outcome == VelocityHandshakeSniffer.AddressOutcome.PATCHED
                        ? "via reflective fallback" : "via PROXY message");
            } else if (log.isDebugEnabled()) {
                log.debug("[zstdnet] restored real IP {} -> {} ({})", backend, forwarded, outcome);
            }
        }

        @Override
        public void compressionRaised(SocketAddress connection, int from, int to) {
            Logger log = logger;
            if (log == null) {
                return;
            }
            if (FIRST_COMPRESSION_LOGGED.compareAndSet(false, true)) {
                log.info("[zstdnet] Velocity compression threshold raised {} -> {} for ZstdNet connection {}"
                    + " (logged once, later connections are handled silently).", from, to, connection);
            } else if (log.isDebugEnabled()) {
                log.debug("[zstdnet] compression threshold {} -> {} for {}", from, to, connection);
            }
        }

        @Override
        public void compressionKeptForEncrypted(SocketAddress connection) {
            Logger log = logger;
            if (log == null) {
                return;
            }
            if (FIRST_ENCRYPTED_LOGGED.compareAndSet(false, true)) {
                log.info("[zstdnet] ZstdNet connection {} is encrypted by Velocity's online-mode login (it did not go"
                    + " through zstdnet:auth), so Velocity's zlib compression is kept for it: ZSTD cannot shrink"
                    + " encrypted traffic. Enable premium_verification to keep ZstdNet players unencrypted"
                    + " (logged once).", connection);
            } else if (log.isDebugEnabled()) {
                log.debug("[zstdnet] kept Velocity compression for encrypted ZstdNet connection {}", connection);
            }
        }

        @Override
        public void warnOnce(String key, String message) {
            VelocityPipelineInjector.warnOnce(key, message);
        }
    }

    /** 带过期时间的布尔配置缓存；读失败时沿用上次的值（首次失败按默认 true）。 */
    static final class CachedFlag implements BooleanSupplier {
        private final BooleanSupplier source;
        private final long ttlNanos;
        private volatile boolean value = true;
        private volatile long readAt;
        private volatile boolean loaded;

        CachedFlag(BooleanSupplier source, long ttlNanos) {
            this.source = source;
            this.ttlNanos = ttlNanos;
        }

        @Override
        public boolean getAsBoolean() {
            long now = System.nanoTime();
            if (!loaded || now - readAt > ttlNanos) {
                try {
                    value = source.getAsBoolean();
                } catch (Throwable ignored) {
                    // 沿用旧值
                }
                readAt = now;
                loaded = true;
            }
            return value;
        }
    }
}
