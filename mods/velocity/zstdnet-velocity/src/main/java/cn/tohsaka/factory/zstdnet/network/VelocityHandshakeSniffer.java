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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * 每条入站连接一个的一次性处理器：只看第一帧，判完立刻自摘。挂在 Velocity 子通道管线的<b>最前面</b>
 * （先于 {@code HAProxyMessageDecoder} / {@code legacy-ping-decoder} / {@code frame-decoder}），看到的是原始字节。
 *
 * <ul>
 *   <li><b>不可信来源</b>（既不是回环、也不是本机网卡地址）：在 {@code handlerAdded} 里立即自摘——公网玩家零开销，
 *       也就无从伪造标记。</li>
 *   <li><b>可信来源、首帧是带 {@code zstdnet-real-ip=} 标记的登录握手</b>（即 ZstdNet 服务端代理转发来的玩家）：
 *     <ol>
 *       <li>若 Velocity 开了 {@code haproxy-protocol}，先从本连接管线里摘掉它的 {@code HAProxyMessageDecoder}
 *           （本机代理的连接不带 PROXY 头，留着会被它当非法连接断开）；</li>
 *       <li>开了真实 IP 还原时，在握手字节之前向下游发一条 netty {@link HAProxyMessage}——Velocity 的
 *           {@code MinecraftConnection#channelRead} 收到它会把连接的 {@code remoteAddress} 换成其中的源地址
 *           （3.x / 4.x 相同，haproxy-protocol 本就走这条路），中间的解码器对非 ByteBuf 消息一律透传；
 *           随后反射核对一次，没生效则按「字段当前值 == 回环地址」兜底改写（同 Bukkit 插件）；</li>
 *       <li>把连接登记进 {@link ZstdnetConnections}（键 = Velocity 之后报告的地址），连接关闭时注销；</li>
 *       <li>挂上 {@link VelocityCompressionTuner}，把 Velocity 对该连接的出站 zlib 阈值抬高；</li>
 *       <li>把<b>剥净标记</b>后的握手字节交给下游——Velocity 永远看不到该标记。</li>
 *     </ol></li>
 *   <li><b>可信来源的其它连接</b>（状态查询、PROXY 头、无标记握手……）：原样放行后自摘。唯一例外：Velocity 开了
 *       haproxy-protocol 时，本机代理透传来的状态查询握手同样不带 PROXY 头，也会替它摘掉 {@code HAProxyMessageDecoder}，
 *       让经 ZstdNet 端口的服务器列表 ping 照常可用（真正负载均衡器来的连接首包是 PROXY 头，不受影响）。</li>
 * </ul>
 *
 * <p>整个处理是尽力而为的：任何一步异常都只降级为「原样放行 / 不还原」并告警一次，绝不断开、不拖延连接。</p>
 */
final class VelocityHandshakeSniffer extends ChannelInboundHandlerAdapter {

    static final String NAME = "zstdnet-handshake";
    /** Velocity {@code Connections.HANDLER}：{@code MinecraftConnection} 在管线里的名字。 */
    static final String CONNECTION_HANDLER = "handler";
    /** 攒到这么多字节还判不出是不是握手，就放弃、原样放行（防呆，正常握手一两个包内必定判完）。 */
    static final int MAX_PENDING_BYTES = 4096;

    /** 注入器 → 处理器的全部外部依赖；单测里全部可替换（EmbeddedChannel 的远端地址不是 InetSocketAddress）。 */
    static final class Settings {
        final Function<Channel, SocketAddress> peerAddress;
        final Predicate<SocketAddress> trustedPeer;
        final BooleanSupplier realIpForwarding;
        /** Velocity 当前的 {@code compression-threshold}；{@code <0} 表示关闭压缩，此时不挂调阈值处理器。 */
        final IntSupplier velocityCompressionThreshold;
        final int zstdCompressionThreshold;
        final Reporter reporter;

        Settings(Function<Channel, SocketAddress> peerAddress,
                 Predicate<SocketAddress> trustedPeer,
                 BooleanSupplier realIpForwarding,
                 IntSupplier velocityCompressionThreshold,
                 int zstdCompressionThreshold,
                 Reporter reporter) {
            this.peerAddress = Objects.requireNonNull(peerAddress, "peerAddress");
            this.trustedPeer = Objects.requireNonNull(trustedPeer, "trustedPeer");
            this.realIpForwarding = Objects.requireNonNull(realIpForwarding, "realIpForwarding");
            this.velocityCompressionThreshold = Objects.requireNonNull(velocityCompressionThreshold, "velocityCompressionThreshold");
            this.zstdCompressionThreshold = zstdCompressionThreshold;
            this.reporter = reporter != null ? reporter : Reporter.NONE;
        }
    }

    /** 真实地址是怎么让 Velocity 采纳的。 */
    enum AddressOutcome {
        /** HAProxyMessage 已被 Velocity 采纳（反射核对到新地址）。 */
        VERIFIED,
        /** HAProxyMessage 没生效，已按值反射改写地址字段。 */
        PATCHED,
        /** 无法核对（找不到连接对象 / 字段形状不认识）。 */
        UNVERIFIED
    }

    /** 日志出口（注入器实现为 slf4j；单测里记录下来断言）。 */
    interface Reporter {
        Reporter NONE = new Reporter() {
        };

        default void restored(SocketAddress backend, SocketAddress forwarded, AddressOutcome outcome) {
        }

        default void compressionRaised(SocketAddress connection, int from, int to) {
        }

        /** 该 ZstdNet 连接已加密，保留了 Velocity 的 zlib 压缩（密文 ZSTD 压不动）。 */
        default void compressionKeptForEncrypted(SocketAddress connection) {
        }

        /** 同一类问题（按 key）只告警一次。 */
        default void warnOnce(String key, String message) {
        }
    }

    private final Settings settings;
    private SocketAddress peer;
    private ByteBuf cumulation;
    private boolean done;

    VelocityHandshakeSniffer(Settings settings) {
        this.settings = settings;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        SocketAddress address;
        boolean trusted;
        try {
            address = settings.peerAddress.apply(ctx.channel());
            trusted = address != null && settings.trustedPeer.test(address);
        } catch (Throwable t) {
            address = null;
            trusted = false;
        }
        if (!trusted) {
            done = true;
            detach(ctx);
            return;
        }
        peer = address;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (done || !(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf in = (ByteBuf) msg;
        ByteBuf data;
        byte[] bytes;
        try {
            if (cumulation != null) {
                try {
                    cumulation.writeBytes(in);
                } finally {
                    in.release();
                }
                data = cumulation;
            } else {
                data = in;
            }
            bytes = ByteBufUtil.getBytes(data);
        } catch (Throwable t) {
            // 读不出来就别改了：把手上的字节原样交出去。
            done = true;
            ByteBuf pending = cumulation != null ? cumulation : in;
            cumulation = null;
            ctx.fireChannelRead(pending);
            detach(ctx);
            return;
        }

        RealIpHandshake.Result result;
        try {
            result = RealIpHandshake.parse(bytes);
        } catch (Throwable t) {
            settings.reporter.warnOnce("parse", "handshake parse failed, passing the connection through untouched: " + t);
            result = null;
            bytes = null; // 强制放行
        }

        if (result == null && bytes != null && bytes.length < MAX_PENDING_BYTES) {
            // 首帧还没收全：攒着等下一段（数据量有上限）。
            if (cumulation == null) {
                cumulation = ctx.alloc().heapBuffer(Math.max(256, bytes.length * 2));
                cumulation.writeBytes(in);
                in.release();
            }
            return;
        }

        done = true;
        cumulation = null; // data 的所有权交给下面
        try {
            forward(ctx, data, result);
        } finally {
            detach(ctx);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // 极端情况下（连接在判完之前被别人摘掉本处理器）别把已收到的字节吞掉。
        if (!done && cumulation != null) {
            ByteBuf pending = cumulation;
            cumulation = null;
            done = true;
            ctx.fireChannelRead(pending);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
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

    // ------------------------------------------------------------------
    // 判定完成后的转发
    // ------------------------------------------------------------------

    private void forward(ChannelHandlerContext ctx, ByteBuf data, RealIpHandshake.Result result) {
        if (result == null || result.realIp == null) {
            // 非 ZstdNet 连接：原样（同一个 ByteBuf）放行。
            if (result != null && !result.proxyHeader && result.nextState == 1) {
                // 本机代理透传的状态查询：haproxy-protocol 开着时同样替它摘掉 PROXY 解码器（没开则什么都不做）。
                removeHaproxyDecoder(ctx.pipeline());
            }
            ctx.fireChannelRead(data);
            return;
        }

        byte[] payload = result.payload;
        data.release();

        SocketAddress reported = peer;
        String realIp = result.realIp;
        try {
            removeHaproxyDecoder(ctx.pipeline());

            InetAddress real = RealIpHandshake.parseIpLiteral(realIp);
            if (real == null) {
                settings.reporter.warnOnce("bad-address", "ignored an invalid forwarded address '" + realIp + "'");
            } else {
                real = InetAddress.getByAddress(real.getAddress()); // 去掉 scope / 主机名，保证与 Velocity 的键 equals
                realIp = real.getHostAddress();
                if (readRealIpForwarding()) {
                    SocketAddress forwarded = restore(ctx, real);
                    if (forwarded != null) {
                        reported = forwarded;
                    }
                }
            }

            ZstdnetConnections.Info info = new ZstdnetConnections.Info(realIp, result.host, result.protocol, System.nanoTime());
            ZstdnetConnections.register(reported, info);
            SocketAddress key = reported;
            ctx.channel().closeFuture().addListener(future -> ZstdnetConnections.unregister(key, info));

            installCompressionTuner(ctx, reported);
        } catch (Throwable t) {
            settings.reporter.warnOnce("marker", "failed to set up a ZstdNet connection (" + t
                + "); it continues without real IP restore / compression tuning.");
        }

        // 无论上面成功与否：交给 Velocity 的一定是剥净标记后的握手。
        ByteBuf out = ctx.alloc().buffer(payload.length);
        out.writeBytes(payload);
        ctx.fireChannelRead(out);
    }

    private boolean readRealIpForwarding() {
        try {
            return settings.realIpForwarding.getAsBoolean();
        } catch (Throwable t) {
            return true; // 与配置默认值一致
        }
    }

    /**
     * 让 Velocity 把本连接的远端地址当成 {@code real:回环源端口}。
     *
     * @return Velocity 之后会报告的地址；彻底没办法时返回 {@code null}（调用方按原回环地址登记）
     */
    private SocketAddress restore(ChannelHandlerContext ctx, InetAddress real) {
        int port = peer instanceof InetSocketAddress ? ((InetSocketAddress) peer).getPort() : 0;
        InetSocketAddress forwarded = new InetSocketAddress(real, port);

        boolean sent = false;
        try {
            ctx.fireChannelRead(proxyMessage(real, port, ctx.channel().localAddress()));
            sent = true;
        } catch (Throwable t) {
            settings.reporter.warnOnce("haproxy-message", "could not hand the real address to Velocity as a PROXY"
                + " message (" + t + "); trying the reflective fallback.");
        }

        AddressOutcome outcome = ensureReportedAddress(ctx.pipeline(), peer, forwarded);
        if (outcome == AddressOutcome.UNVERIFIED) {
            if (ctx.pipeline().get(CONNECTION_HANDLER) != null) {
                settings.reporter.warnOnce("unverified", "could not confirm that Velocity picked up the restored"
                    + " address; ZstdNet players may still show as a loopback address on this Velocity build.");
            }
            if (!sent) {
                return null;
            }
        }
        settings.reporter.restored(peer, forwarded, outcome);
        return forwarded;
    }

    /** 以 v2 / PROXY / TCP4|TCP6 构造；Velocity 只取 {@code sourceAddress/sourcePort}，目的地址用本端地址或同族占位。 */
    static HAProxyMessage proxyMessage(InetAddress source, int sourcePort, SocketAddress local) {
        boolean v4 = source instanceof Inet4Address;
        String destination = v4 ? "127.0.0.1" : "::1";
        int destinationPort = 0;
        if (local instanceof InetSocketAddress) {
            InetSocketAddress localInet = (InetSocketAddress) local;
            InetAddress localIp = localInet.getAddress();
            if (localIp != null && !localIp.isAnyLocalAddress()
                && (v4 ? localIp instanceof Inet4Address : localIp instanceof Inet6Address)) {
                try {
                    destination = InetAddress.getByAddress(localIp.getAddress()).getHostAddress();
                } catch (Exception ignored) {
                    // 保持占位地址
                }
            }
            destinationPort = localInet.getPort();
        }
        return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
            v4 ? HAProxyProxiedProtocol.TCP4 : HAProxyProxiedProtocol.TCP6,
            source.getHostAddress(), destination, sourcePort, destinationPort);
    }

    private void installCompressionTuner(ChannelHandlerContext ctx, SocketAddress reported) {
        int velocityThreshold;
        try {
            velocityThreshold = settings.velocityCompressionThreshold.getAsInt();
        } catch (Throwable t) {
            velocityThreshold = 0; // 读不到配置：照样挂上，调阈值时再按编码器的实际值判断
        }
        int target = settings.zstdCompressionThreshold;
        if (velocityThreshold < 0 || velocityThreshold >= target) {
            return; // Velocity 关了压缩 / 本就不低于目标值：不动
        }
        try {
            if (ctx.pipeline().get(VelocityCompressionTuner.NAME) == null) {
                ctx.pipeline().addFirst(VelocityCompressionTuner.NAME,
                    new VelocityCompressionTuner(target, settings.reporter, reported));
            }
        } catch (Throwable t) {
            settings.reporter.warnOnce("compression-hook", "could not hook compression tuning: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 管线 / 反射小工具
    // ------------------------------------------------------------------

    /** 摘掉本连接管线里 Velocity 的 {@code HAProxyMessageDecoder}（它是无名 addFirst 进来的，按类型找）。 */
    private boolean removeHaproxyDecoder(ChannelPipeline pipeline) {
        try {
            for (Map.Entry<String, ChannelHandler> entry : pipeline) { // 迭代的是快照，边遍历边删安全
                ChannelHandler handler = entry.getValue();
                if (handler instanceof HAProxyMessageDecoder
                    || "HAProxyMessageDecoder".equals(handler.getClass().getSimpleName())) {
                    pipeline.remove(handler);
                    return true;
                }
            }
        } catch (Throwable t) {
            settings.reporter.warnOnce("haproxy-decoder", "could not bypass Velocity's haproxy-protocol decoder for"
                + " a ZstdNet connection: " + t);
        }
        return false;
    }

    /**
     * 核对 Velocity 的连接对象（管线里名为 {@code handler} 的 {@code MinecraftConnection}）是否已采纳新地址；
     * 没有则把「当前值 == 回环地址」的 {@code SocketAddress} 字段改成新地址（按值匹配，不依赖字段名）。
     */
    static AddressOutcome ensureReportedAddress(ChannelPipeline pipeline, SocketAddress original, SocketAddress forwarded) {
        ChannelHandler handler = pipeline.get(CONNECTION_HANDLER);
        if (handler == null || original == null) {
            return AddressOutcome.UNVERIFIED;
        }
        List<Field> fields = SOCKET_ADDRESS_FIELDS.get(handler.getClass());
        try {
            for (Field field : fields) {
                if (forwarded.equals(field.get(handler))) {
                    return AddressOutcome.VERIFIED;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就走下面的改写
        }
        boolean replaced = false;
        for (Field field : fields) {
            try {
                if (original.equals(field.get(handler))) {
                    field.set(handler, forwarded);
                    replaced = true;
                }
            } catch (Throwable ignored) {
                // 模块系统挡下 / final 字段：跳过
            }
        }
        return replaced ? AddressOutcome.PATCHED : AddressOutcome.UNVERIFIED;
    }

    private static final ClassValue<List<Field>> SOCKET_ADDRESS_FIELDS = new ClassValue<List<Field>>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !SocketAddress.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (Throwable ignored) {
                        // 打不开就不用它
                    }
                }
            }
            return fields;
        }
    };
}
