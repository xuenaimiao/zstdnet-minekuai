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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.SocketAddress;

/**
 * 经 ZstdNet 进来的连接上「抬高 Velocity 出站 zlib 压缩阈值」的一次性处理器。
 *
 * <p><b>为什么</b>：Velocity 自己会对发往客户端的包做 zlib 压缩（{@code compression-threshold}，默认 256）。
 * 这些包再交给 ZstdNet 服务端代理做 ZSTD 时已经是高熵数据，几乎压不动；ZstdNet 的 transform/区块缓存也要求
 * play 阶段 {@code dataLength == 0}（未压缩）。mod 变体的做法是把后端的 {@code network-compression-threshold}
 * 强制成 {@value #ZSTD_COMPRESSION_THRESHOLD}，这里对 Velocity 做等价的事——但只针对 ZstdNet 连接，
 * 原版直连玩家保持 Velocity 默认。</p>
 *
 * <p><b>为什么只改编码器、这样改是安全的</b>：Velocity 在登录时先发 SetCompression(阈值) 再装
 * {@code compression-encoder}（{@code MinecraftCompressorAndLengthEncoder}）/{@code compression-decoder}。
 * 我们只把编码器的阈值调大：小于新阈值的包以 {@code dataLength=0} 原样发出。原版客户端的 CompressionDecoder
 * 对 {@code dataLength=0} 的包不做任何大小校验（只校验压缩过的包，且客户端侧本就不开校验），所以客户端完全无感；
 * 客户端仍按 SetCompression 里的阈值压缩它发来的包，Velocity 的解码器阈值不动，两边依旧一致。
 * 1 MiB 以内的未压缩帧也远低于 2 MiB 的帧上限。</p>
 *
 * <p><b>怎么挂</b>：由 {@link VelocityHandshakeSniffer} 在识别出 ZstdNet 连接时放到管线最前。
 * Velocity 装好压缩处理器后会立刻 {@code fireUserEventTriggered(COMPRESSION_ENABLED)}——入站事件从管线头部开始传，
 * 我们第一个收到，于是在 LoginSuccess 等后续包写出之前就把阈值改好，然后自摘。
 * 兜底：若某版本不再发该事件，则在出站写经过时检查编码器是否已出现（此时顶多有一个包按旧阈值发出）。
 * 迟迟等不到（例如 1.7 客户端、或 Velocity 关了压缩）就在若干次写之后放弃自摘，不常驻管线。</p>
 *
 * <p>反射任何一步失败都只告警一次并保持 Velocity 默认行为，绝不影响连接。</p>
 */
final class VelocityCompressionTuner extends ChannelDuplexHandler {

    static final String NAME = "zstdnet-compression";
    /** Velocity {@code Connections.COMPRESSION_ENCODER}，3.x / 4.x 相同。 */
    static final String ENCODER_NAME = "compression-encoder";
    /** Velocity {@code Connections.CIPHER_ENCODER}，3.x / 4.x 相同；存在即说明该连接已开 AES。 */
    static final String CIPHER_ENCODER_NAME = "cipher-encoder";
    /** 与 mod 变体强制的 {@code network-compression-threshold} 相同（见 {@code DedicatedServerAutoPort}）。 */
    static final int ZSTD_COMPRESSION_THRESHOLD = 1048576;
    /** 登录阶段在 SetCompression 之前的出站包屈指可数；等过这么多次写仍没有编码器就放弃。 */
    static final int MAX_WRITES_WITHOUT_ENCODER = 64;

    private final int target;
    private final VelocityHandshakeSniffer.Reporter reporter;
    private final SocketAddress connection;
    private int writes;
    private boolean finished;

    VelocityCompressionTuner(int target, VelocityHandshakeSniffer.Reporter reporter, SocketAddress connection) {
        this.target = target;
        this.reporter = reporter;
        this.connection = connection;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        boolean done = false;
        if (!finished) {
            try {
                done = isCompressionDisabled(evt) || tryPatch(ctx);
            } catch (Throwable t) {
                done = true;
                reporter.warnOnce("compression-event", "could not adjust Velocity's compression for a ZstdNet connection: " + t);
            }
        }
        ctx.fireUserEventTriggered(evt);
        if (done) {
            finish(ctx);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean done = false;
        if (!finished) {
            try {
                done = tryPatch(ctx) || ++writes >= MAX_WRITES_WITHOUT_ENCODER;
            } catch (Throwable t) {
                done = true;
                reporter.warnOnce("compression-write", "could not adjust Velocity's compression for a ZstdNet connection: " + t);
            }
        }
        ctx.write(msg, promise);
        if (done) {
            finish(ctx);
        }
    }

    /** @return 是否已经处理完（编码器已出现，不论改没改成） */
    private boolean tryPatch(ChannelHandlerContext ctx) {
        ChannelHandler encoder = ctx.pipeline().get(ENCODER_NAME);
        if (encoder == null) {
            return false;
        }
        // 连接已开 AES（Velocity 在线模式下没走 zstdnet:auth：正版验证关闭、1.16.5 等不支持带外验证的客户端、
        // 或其他插件强制了在线验证）。Velocity 的加密在压缩之前装好，故此刻能看到 cipher-encoder。
        // 密文 ZSTD 压不动，这时去掉 zlib 只会让流量变大——保留 Velocity 默认阈值。
        if (ctx.pipeline().get(CIPHER_ENCODER_NAME) != null) {
            reporter.compressionKeptForEncrypted(connection);
            return true;
        }
        Outcome outcome;
        try {
            outcome = raiseThreshold(encoder, target);
        } catch (Throwable t) {
            outcome = Outcome.unsupported(t.toString());
        }
        if (outcome.kind == Outcome.Kind.RAISED) {
            reporter.compressionRaised(connection, outcome.previous, target);
        } else if (outcome.kind == Outcome.Kind.UNSUPPORTED) {
            reporter.warnOnce("compression-reflect", "could not raise Velocity's compression threshold for ZstdNet"
                + " connections (" + outcome.detail + "); they keep Velocity's zlib compression, which ZSTD cannot"
                + " shrink much further. Everything else keeps working.");
        }
        return true;
    }

    private void finish(ChannelHandlerContext ctx) {
        finished = true;
        try {
            ctx.pipeline().remove(this);
        } catch (Throwable ignored) {
            // 已被摘除 / 管线已拆：无需处理。
        }
    }

    /** Velocity 的 {@code VelocityConnectionEvent.COMPRESSION_DISABLED}（按枚举名匹配，不依赖 Velocity 内部类）。 */
    private static boolean isCompressionDisabled(Object evt) {
        return evt instanceof Enum && "COMPRESSION_DISABLED".equals(((Enum<?>) evt).name());
    }

    // ------------------------------------------------------------------
    // 反射：MinecraftCompressorAndLengthEncoder { private int threshold; public void setThreshold(int); }
    // ------------------------------------------------------------------

    /** 一次调阈值的结果。 */
    static final class Outcome {
        enum Kind { RAISED, ALREADY_HIGH, DISABLED, UNSUPPORTED }

        final Kind kind;
        final int previous;
        final String detail;

        private Outcome(Kind kind, int previous, String detail) {
            this.kind = kind;
            this.previous = previous;
            this.detail = detail;
        }

        static Outcome unsupported(String detail) {
            return new Outcome(Kind.UNSUPPORTED, -1, detail);
        }
    }

    private static final class Access {
        final Field field;
        final Method setter;

        Access(Field field, Method setter) {
            this.field = field;
            this.setter = setter;
        }
    }

    private static final ClassValue<Access> ACCESS = new ClassValue<Access>() {
        @Override
        protected Access computeValue(Class<?> type) {
            return new Access(findThresholdField(type), findSetter(type));
        }
    };

    /**
     * 把编码器的阈值抬到 {@code target}。优先调公开的 {@code setThreshold(int)}（Velocity 自己也是这么改的），
     * 没有就直接写 {@code int threshold} 字段；有字段时先读当前值：{@code <0}（压缩已关）或已不低于目标值就不动。
     */
    static Outcome raiseThreshold(Object encoder, int target) throws ReflectiveOperationException {
        Access access = ACCESS.get(encoder.getClass());
        if (access.field == null && access.setter == null) {
            return Outcome.unsupported("no 'threshold' field or setThreshold(int) on " + encoder.getClass().getName());
        }
        int current = -1;
        if (access.field != null) {
            current = access.field.getInt(encoder);
            if (current < 0) {
                return new Outcome(Outcome.Kind.DISABLED, current, null);
            }
            if (current >= target) {
                return new Outcome(Outcome.Kind.ALREADY_HIGH, current, null);
            }
        }
        if (access.setter != null) {
            access.setter.invoke(encoder, target);
        } else {
            access.field.setInt(encoder, target);
        }
        if (access.field != null && access.field.getInt(encoder) != target) {
            return Outcome.unsupported("threshold did not change on " + encoder.getClass().getName());
        }
        return new Outcome(Outcome.Kind.RAISED, current, null);
    }

    private static Field findThresholdField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("threshold");
                if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // 继续找父类。
            } catch (Throwable t) {
                return null; // 模块系统挡下等：当作没有这个字段。
            }
        }
        return null;
    }

    private static Method findSetter(Class<?> type) {
        try {
            Method method = type.getMethod("setThreshold", int.class);
            if (Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            // 公开类的公开方法无需 setAccessible；声明类不公开时（测试替身）再尝试打开。
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                method.setAccessible(true);
            }
            return method;
        } catch (Throwable t) {
            return null;
        }
    }
}
