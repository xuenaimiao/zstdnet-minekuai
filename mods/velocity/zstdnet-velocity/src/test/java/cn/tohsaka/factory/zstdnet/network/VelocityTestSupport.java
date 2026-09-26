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
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 管线注入单测共用的造包工具与 Velocity 内部类的替身（字段/方法形状与 3.5.1 / 4.2.0 一致）。 */
final class VelocityTestSupport {

    static final int PROTOCOL = 763;

    private VelocityTestSupport() {
    }

    static byte[] handshake(String host, int port, int nextState) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        byte[] payload = ByteArrayOps.concat(
            VarIntCodec.encode(0),
            VarIntCodec.encode(PROTOCOL),
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            new byte[] {(byte) (port >> 8), (byte) port},
            VarIntCodec.encode(nextState)
        );
        return ByteArrayOps.concat(VarIntCodec.encode(payload.length), payload);
    }

    static String marker(String ip) {
        return "\0zstdnet-real-ip="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(ip.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] drain(ByteBuf buf) {
        try {
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /** 记录下所有回调，供断言。 */
    static final class RecordingReporter implements VelocityHandshakeSniffer.Reporter {
        final List<VelocityHandshakeSniffer.AddressOutcome> outcomes = new ArrayList<>();
        final List<SocketAddress> restoredTo = new ArrayList<>();
        final List<int[]> raised = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<SocketAddress> keptEncrypted = new ArrayList<>();

        @Override
        public void restored(SocketAddress backend, SocketAddress forwarded, VelocityHandshakeSniffer.AddressOutcome outcome) {
            outcomes.add(outcome);
            restoredTo.add(forwarded);
        }

        @Override
        public void compressionRaised(SocketAddress connection, int from, int to) {
            raised.add(new int[] {from, to});
        }

        @Override
        public void compressionKeptForEncrypted(SocketAddress connection) {
            keptEncrypted.add(connection);
        }

        @Override
        public void warnOnce(String key, String message) {
            warnings.add(key);
        }
    }

    /**
     * {@code MinecraftConnection} 的替身：私有 {@code SocketAddress remoteAddress} 字段，
     * {@code honorsProxyMessages=true} 时像 Velocity 一样从 HAProxyMessage 取源地址。
     */
    static final class FakeConnection extends ChannelInboundHandlerAdapter {
        private SocketAddress remoteAddress;
        private final boolean honorsProxyMessages;
        final List<Object> received = new ArrayList<>();

        FakeConnection(SocketAddress remoteAddress, boolean honorsProxyMessages) {
            this.remoteAddress = remoteAddress;
            this.honorsProxyMessages = honorsProxyMessages;
        }

        SocketAddress remoteAddress() {
            return remoteAddress;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HAProxyMessage) {
                HAProxyMessage proxy = (HAProxyMessage) msg;
                if (honorsProxyMessages) {
                    remoteAddress = new InetSocketAddress(proxy.sourceAddress(), proxy.sourcePort());
                }
                proxy.release();
                return;
            }
            received.add(msg);
        }
    }

    /** {@code MinecraftCompressorAndLengthEncoder} 的替身：{@code private int threshold} + {@code public setThreshold(int)}。 */
    public static final class FakeCompressorEncoder extends ChannelOutboundHandlerAdapter {
        private int threshold;

        FakeCompressorEncoder(int threshold) {
            this.threshold = threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        int threshold() {
            return threshold;
        }
    }

    /** 只有字段、没有 setter 的编码器（走字段兜底）。 */
    static final class FieldOnlyEncoder extends ChannelOutboundHandlerAdapter {
        private int threshold;

        FieldOnlyEncoder(int threshold) {
            this.threshold = threshold;
        }

        int threshold() {
            return threshold;
        }
    }

    /** 形状完全不认识的编码器。 */
    static final class UnknownEncoder extends ChannelOutboundHandlerAdapter {
    }

    /** 与 Velocity {@code VelocityConnectionEvent} 同名的枚举（调阈值处理器按枚举名匹配）。 */
    enum FakeConnectionEvent {
        COMPRESSION_ENABLED,
        COMPRESSION_DISABLED,
        ENCRYPTION_ENABLED
    }

    /** 记录传到管线尾部的用户事件。 */
    static final class EventRecorder extends ChannelInboundHandlerAdapter {
        final List<Object> events = new ArrayList<>();

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            events.add(evt);
        }
    }
}
