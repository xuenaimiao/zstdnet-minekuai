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

import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件端「真实 IP 还原」（对应 mod 变体的 {@code coremod/ServerRealIpHooks} + coremod/mixin 注入）。
 *
 * <p><b>要解决什么</b>：插件端的 zstd 代理是独立监听端口 → 转发到 {@code 127.0.0.1:server-port}，
 * 所以后端 MC 看到的 remote address 恒为回环地址，进服日志、{@code /ban-ip}、连接节流、按 IP 的风控插件
 * 全都只能看到 {@code 127.0.0.1}。</p>
 *
 * <p><b>怎么做</b>：{@link cn.tohsaka.factory.zstdnet.server.ServerProxyRuntime} 转发登录握手时，已经在
 * host 字段里追加了一段 {@code \0zstdnet-real-ip=<base64(ip)>} 标记（mod 变体的 coremod 读的就是它）。
 * 本类在服务器自己的 netty 监听通道上挂一个 acceptor，为每条新进来的连接在管线**最前面**插一个一次性处理器：
 * 读到第一帧时按原版握手线格式解析 → 取出并**剥掉**该标记（不让后端看见，避免与 spigot.yml 的
 * BungeeCord 转发等同样按 {@code \0} 分段的机制打架）→ 反射把 {@code Connection} 里那个值等于本连接回环地址的
 * {@code SocketAddress} 字段替换成真实地址 → 把自己从管线摘掉。之后该连接逐字节与未注入时一致。</p>
 *
 * <p><b>为什么是版本无关的</b>：全程只碰 netty 类型与原版握手包的线格式（{@code [varint len][0x00]
 * [varint protocol][string host][ushort port][varint next_state]}，从 1.7 起没变过），不引用任何
 * {@code net.minecraft.*} 类、不依赖字段名（Spigot 混淆名 / Paper Mojang 名都能命中，因为是**按类型 + 按当前值**
 * 匹配要替换的字段）。所以同一个 jar 在 1.20.1 与 1.21.x 上都能用。</p>
 *
 * <p><b>安全边界</b>：标记只在「连接来自回环地址」时才被采信——与 mod 变体的 {@code isTrustedLocalProxy}
 * 同一条规则。公网玩家直连 server-port 时本处理器在 {@code handlerAdded} 里立刻自摘，既不缓冲也不解析，
 * 因此伪造不了自己的 IP，对未走代理的连接开销为零。</p>
 *
 * <p>任何一步失败（反射被模块系统挡下、管线结构异常等）都只降级为「不还原」并记一条日志，
 * 绝不影响连接本身；整功能也可用配置项 {@code real_ip_forwarding=false} 一键关闭。</p>
 */
public final class RealIpForwarding {

    private static final String ACCEPTOR_HANDLER = "zstdnet_real_ip_acceptor";
    private static final String CHILD_HANDLER = "zstdnet_real_ip";
    /** 攒到这么多字节还判不出是不是握手，就放弃、原样放行（防呆，正常握手一两个包内必定判完）。 */
    private static final int MAX_PENDING_BYTES = 4096;
    /** 首次注入没找到监听通道时的重试等待（混合端可能晚绑端口）。 */
    private static final long RETRY_DELAY_MILLIS = 3000L;

    private static final Acceptor ACCEPTOR = new Acceptor();
    private static final List<Channel> INJECTED = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean FIRST_RESTORE_LOGGED = new AtomicBoolean();

    private static volatile Logger logger;

    private RealIpForwarding() {
    }

    /**
     * 插件 onEnable 时调用：按配置在服务器监听通道上挂 acceptor。
     * <p>插件在 MC 端口绑定之后才加载（{@code load: POSTWORLD}），所以此时监听通道已经存在；
     * 万一没找到（混合端晚绑），后台延迟重试一次。
     */
    public static void init(Plugin plugin) {
        logger = plugin.getLogger();

        if (!ServerProxyConfigFile.readRealIpForwarding()) {
            logger.info("[zstdnet] real IP restore is off (real_ip_forwarding=false);"
                + " players coming through the proxy will show as 127.0.0.1.");
            return;
        }

        if (injectAll()) {
            return;
        }
        // 没找到监听通道：后台等一会儿再试一次，仍失败才告警（不阻塞 onEnable）。
        Thread retry = new Thread(() -> {
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!injectAll()) {
                warn("could not locate the server's netty listener; real IP restore is inactive."
                    + " Players coming through the proxy will keep showing as 127.0.0.1."
                    + " Set real_ip_forwarding=false in " + ServerProxyConfigFile.path() + " to silence this.");
            }
        }, "zstdnet-real-ip-retry");
        retry.setDaemon(true);
        retry.start();
    }

    /**
     * 插件 onDisable 时调用：把 acceptor 从监听通道摘掉（/reload 重载插件时避免重复注入）。
     * 已建立连接上的一次性处理器会自行摘除，无需处理。
     */
    public static void shutdown() {
        for (Channel channel : INJECTED) {
            try {
                if (channel.pipeline().get(ACCEPTOR_HANDLER) != null) {
                    channel.pipeline().remove(ACCEPTOR_HANDLER);
                }
            } catch (Throwable ignored) {
                // 通道已关闭 / 管线已拆：无需处理。
            }
        }
        INJECTED.clear();
        FIRST_RESTORE_LOGGED.set(false);
    }

    private static boolean injectAll() {
        List<Channel> channels;
        try {
            channels = findServerChannels();
        } catch (Throwable t) {
            warn("failed to look up the server's netty listener: " + t
                + "; real IP restore is inactive (the proxy itself is unaffected).");
            return true; // 已经明确告警过，别再重试刷屏。
        }

        if (channels.isEmpty()) {
            return false;
        }

        int injected = 0;
        for (Channel channel : channels) {
            try {
                if (channel.pipeline().get(ACCEPTOR_HANDLER) != null) {
                    continue;
                }
                channel.pipeline().addFirst(ACCEPTOR_HANDLER, ACCEPTOR);
                INJECTED.add(channel);
                injected++;
            } catch (Throwable t) {
                warn("failed to hook listener " + channel + ": " + t);
            }
        }

        if (injected > 0) {
            logger.info("[zstdnet] real IP restore active on " + injected + " listener(s):"
                + " players arriving through the zstd proxy will show their real address"
                + " (join log, /ban-ip, IP-based plugins). Vanilla direct connections are untouched.");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 定位服务器的 netty 监听通道
    // ------------------------------------------------------------------

    /**
     * {@code CraftServer#getServer() -> MinecraftServer -> ServerConnectionListener -> List<ChannelFuture>}。
     * 全程按「类型」而不是「名字」找，混淆端（Spigot）与 Mojang 映射端（Paper 1.20.5+）都能命中。
     */
    private static List<Channel> findServerChannels() throws ReflectiveOperationException {
        Object craftServer = Bukkit.getServer();
        Object mcServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        Object connectionListener = findConnectionListener(mcServer);
        if (connectionListener == null) {
            return new ArrayList<>();
        }

        List<Channel> channels = new ArrayList<>();
        for (Class<?> type = connectionListener.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(connectionListener);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                // MC 用 Collections.synchronizedList 包裹，遍历需自行加锁。
                synchronized (list) {
                    for (Object element : list) {
                        if (element instanceof ChannelFuture) {
                            Channel channel = ((ChannelFuture) element).channel();
                            if (channel != null && !channels.contains(channel)) {
                                channels.add(channel);
                            }
                        }
                    }
                }
                if (!channels.isEmpty()) {
                    return channels;
                }
            }
        }
        return channels;
    }

    /** 在 MinecraftServer 里找 ServerConnectionListener（Spigot 混淆名为 ServerConnection）。 */
    private static Object findConnectionListener(Object mcServer) throws ReflectiveOperationException {
        for (Class<?> type = mcServer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getType().getSimpleName();
                if (!name.equals("ServerConnectionListener") && !name.equals("ServerConnection")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(mcServer);
                if (value != null) {
                    return value;
                }
            }
        }
        // 兜底：按「自身持有一个装着 ChannelFuture 的 List」的形状认。
        for (Class<?> type = mcServer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(mcServer);
                if (value != null && holdsChannelFutures(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean holdsChannelFutures(Object candidate) {
        for (Class<?> type = candidate.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(candidate);
                    if (!(value instanceof List)) {
                        continue;
                    }
                    List<?> list = (List<?>) value;
                    synchronized (list) {
                        for (Object element : list) {
                            if (element instanceof ChannelFuture) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 该字段读不到就跳过。
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // netty 处理器
    // ------------------------------------------------------------------

    /**
     * 挂在**监听通道**上：新连接被 accept 时，它的 {@code channelRead} 收到的 msg 就是那条子通道。
     * 此刻子通道管线还是空的（{@code ServerBootstrapAcceptor} 在我们之后才把 MC 的 initializer 加进去），
     * 所以这里 addFirst 的处理器最终稳定位于管线最前——先于 splitter/decoder 看到原始字节。
     */
    @ChannelHandler.Sharable
    private static final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel) {
                try {
                    ((Channel) msg).pipeline().addFirst(CHILD_HANDLER, new HandshakeSniffer());
                } catch (Throwable t) {
                    warnOnce("hook-child", "failed to hook an incoming connection: " + t);
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * 每条连接一个的一次性处理器：只看第一帧，判完（还原 / 放行）立刻自摘。
     * 非回环来源在 {@code handlerAdded} 就自摘，公网玩家直连这条路上零开销。
     */
    private static final class HandshakeSniffer extends ChannelInboundHandlerAdapter {

        private byte[] pending = new byte[0];
        private boolean done;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            if (!isLoopback(ctx.channel().remoteAddress())) {
                detach(ctx);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (done || !(msg instanceof ByteBuf)) {
                ctx.fireChannelRead(msg);
                return;
            }

            ByteBuf in = (ByteBuf) msg;
            byte[] chunk;
            try {
                chunk = new byte[in.readableBytes()];
                in.readBytes(chunk);
            } catch (Throwable t) {
                // 读不出来就别改了，原样放行。
                done = true;
                ctx.fireChannelRead(msg);
                detach(ctx);
                return;
            }
            in.release();

            pending = pending.length == 0 ? chunk : ByteArrayOps.concat(pending, chunk);

            byte[] rewritten;
            try {
                rewritten = handle(ctx, pending);
            } catch (Throwable t) {
                warnOnce("rewrite", "handshake rewrite failed, passing the connection through untouched: " + t);
                rewritten = pending;
            }

            if (rewritten == null) {
                // 还判不出来：等更多字节；攒过头就认栽、原样放行。
                if (pending.length < MAX_PENDING_BYTES) {
                    return;
                }
                rewritten = pending;
            }

            done = true;
            byte[] out = rewritten;
            pending = new byte[0];
            ctx.fireChannelRead(Unpooled.wrappedBuffer(out));
            detach(ctx);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            // 极端情况下（连接在判完之前被摘/关闭）别把已收到的字节吞掉。
            if (!done && pending.length > 0) {
                byte[] out = pending;
                pending = new byte[0];
                done = true;
                ctx.fireChannelRead(Unpooled.wrappedBuffer(out));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            pending = new byte[0];
            done = true;
            ctx.fireChannelInactive();
        }

        private void detach(ChannelHandlerContext ctx) {
            try {
                ctx.pipeline().remove(this);
            } catch (Throwable ignored) {
                // 已被摘除 / 管线已拆：无需处理。
            }
        }

        /**
         * @return 交给后端的字节（命中标记时已剥净）；{@code null}=还需要更多字节才能判定
         */
        private byte[] handle(ChannelHandlerContext ctx, byte[] data) {
            RealIpHandshake.Result result = RealIpHandshake.parse(data);
            if (result == null) {
                return null;
            }
            if (result.realIp == null) {
                return result.payload; // 首帧不是带标记的登录握手：原样放行。
            }

            InetAddress real = RealIpHandshake.parseIpLiteral(result.realIp);
            if (real == null) {
                warnOnce("bad-address", "ignored an invalid forwarded address '" + result.realIp + "'");
                return result.payload;
            }

            SocketAddress backend = ctx.channel().remoteAddress();
            int port = backend instanceof InetSocketAddress ? ((InetSocketAddress) backend).getPort() : 0;
            SocketAddress forwarded = new InetSocketAddress(real, port);
            if (!replaceConnectionAddress(ctx.pipeline(), backend, forwarded)) {
                warnOnce("no-connection", "could not find the connection object to rewrite; real IP restore is not working"
                    + " on this server build. Consider the Forge/NeoForge/Fabric mod build, or set"
                    + " real_ip_forwarding=false to silence this.");
                return result.payload;
            }

            if (FIRST_RESTORE_LOGGED.compareAndSet(false, true)) {
                logger.info("[zstdnet] real IP restore verified: " + backend + " -> " + forwarded
                    + " (logged once; later connections are handled silently).");
            } else if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "[zstdnet] restored real IP {0} -> {1}", new Object[] {backend, forwarded});
            }
            return result.payload;
        }
    }

    // ------------------------------------------------------------------
    // 地址替换
    // ------------------------------------------------------------------

    /**
     * 把管线里那个「当前值 == 本连接回环地址」的 {@code SocketAddress} 字段替换成真实地址。
     * <p>按值匹配而不是按字段名，因此不受混淆映射影响，也不会误伤 Paper 的 {@code haProxyAddress}
     * 之类另有其值（或为 null）的字段。
     */
    private static boolean replaceConnectionAddress(ChannelPipeline pipeline, SocketAddress current, SocketAddress forwarded) {
        if (current == null) {
            return false;
        }

        ChannelHandler primary = pipeline.get("packet_handler");
        if (primary != null && replaceIn(primary, current, forwarded)) {
            return true;
        }

        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            ChannelHandler handler = entry.getValue();
            if (handler == primary || handler instanceof HandshakeSniffer) {
                continue;
            }
            if (replaceIn(handler, current, forwarded)) {
                return true;
            }
        }
        return false;
    }

    private static boolean replaceIn(Object target, SocketAddress current, SocketAddress forwarded) {
        boolean replaced = false;
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !SocketAddress.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (current.equals(field.get(target))) {
                        field.set(target, forwarded);
                        replaced = true;
                    }
                } catch (Throwable ignored) {
                    // 模块系统挡下 / 字段只读：跳过，由调用方按「没换成」处理。
                }
            }
        }
        return replaced;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean isLoopback(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            return false;
        }
        InetAddress ip = ((InetSocketAddress) address).getAddress();
        return ip != null && (ip.isLoopbackAddress() || ip.isAnyLocalAddress());
    }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 同一类问题只告警一次（按 key 区分），避免每条连接刷屏。 */
    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            warn(message);
        }
    }

    private static void warn(String message) {
        Logger log = logger;
        if (log != null) {
            log.warning("[zstdnet] real IP restore: " + message);
        }
    }
}
