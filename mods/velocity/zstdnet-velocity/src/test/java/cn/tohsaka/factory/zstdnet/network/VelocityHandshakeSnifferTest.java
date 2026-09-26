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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static cn.tohsaka.factory.zstdnet.network.VelocityTestSupport.PROTOCOL;
import static cn.tohsaka.factory.zstdnet.network.VelocityTestSupport.drain;
import static cn.tohsaka.factory.zstdnet.network.VelocityTestSupport.handshake;
import static cn.tohsaka.factory.zstdnet.network.VelocityTestSupport.marker;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VelocityHandshakeSniffer} 的管线级测试（EmbeddedChannel）：标记剥离、登记、HAProxyMessage 先于握手、
 * 不可信 / 无标记连接自摘、haproxy 解码器的去留、调阈值处理器的挂载。
 */
class VelocityHandshakeSnifferTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 40001);
    private static final byte[] LOGIN_START = {0x05, 0x00, 0x03, 'a', 'b', 'c'};

    private VelocityTestSupport.RecordingReporter reporter;

    @BeforeEach
    void setUp() {
        ZstdnetConnections.clear();
        reporter = new VelocityTestSupport.RecordingReporter();
    }

    @AfterEach
    void tearDown() {
        ZstdnetConnections.clear();
    }

    private VelocityHandshakeSniffer.Settings settings(boolean trusted, boolean realIp, int velocityThreshold) {
        return settings(PEER, trusted, realIp, velocityThreshold);
    }

    private VelocityHandshakeSniffer.Settings settings(SocketAddress peer, boolean trusted, boolean realIp, int velocityThreshold) {
        return new VelocityHandshakeSniffer.Settings(
            channel -> peer,
            address -> trusted,
            () -> realIp,
            () -> velocityThreshold,
            VelocityCompressionTuner.ZSTD_COMPRESSION_THRESHOLD,
            reporter);
    }

    private static EmbeddedChannel channelWith(VelocityHandshakeSniffer.Settings settings, io.netty.channel.ChannelHandler... velocityHandlers) {
        EmbeddedChannel channel = new EmbeddedChannel(velocityHandlers);
        channel.pipeline().addFirst(VelocityHandshakeSniffer.NAME, new VelocityHandshakeSniffer(settings));
        return channel;
    }

    private static byte[] markedLogin(String ip) {
        return ByteArrayOps.concat(handshake("mc.example.com" + marker(ip), 25577, 2), LOGIN_START);
    }

    private static byte[] cleanLogin() {
        return ByteArrayOps.concat(handshake("mc.example.com", 25577, 2), LOGIN_START);
    }

    @Test
    void stripsTheMarkerSendsProxyMessageFirstAndRegisters() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));

        // 1) 先到的是 HAProxyMessage（Velocity 据此把 remoteAddress 换成真实地址）
        HAProxyMessage proxy = assertInstanceOf(HAProxyMessage.class, channel.readInbound());
        assertEquals("203.0.113.9", proxy.sourceAddress());
        assertEquals(40001, proxy.sourcePort());
        assertEquals(HAProxyProxiedProtocol.TCP4, proxy.proxiedProtocol());
        proxy.release();

        // 2) 然后是剥净标记的握手 + 原样的 login start
        ByteBuf forwarded = assertInstanceOf(ByteBuf.class, channel.readInbound());
        assertArrayEquals(cleanLogin(), drain(forwarded));
        assertNull(channel.readInbound());

        // 3) 自摘；登记键 = 真实 IP + 回环源端口
        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
        ZstdnetConnections.Info info = ZstdnetConnections.lookup(new InetSocketAddress("203.0.113.9", 40001));
        assertNotNull(info);
        assertEquals("203.0.113.9", info.realIp());
        assertEquals("mc.example.com", info.rawHost());
        assertEquals(PROTOCOL, info.protocol());
        assertNull(ZstdnetConnections.lookup(PEER));

        // 4) 挂上了调阈值处理器
        assertNotNull(channel.pipeline().get(VelocityCompressionTuner.NAME));

        // 5) 连接关闭即注销
        channel.close();
        channel.runPendingTasks();
        assertNull(ZstdnetConnections.lookup(new InetSocketAddress("203.0.113.9", 40001)));
        assertEquals(0, ZstdnetConnections.size());
    }

    @Test
    void keepsForgeHostSegmentsInTheRegisteredHost() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));

        channel.writeInbound(Unpooled.wrappedBuffer(handshake("mc.example.com\0FML3\0" + marker("198.51.100.7"), 25577, 2)));

        ((HAProxyMessage) channel.readInbound()).release();
        assertArrayEquals(handshake("mc.example.com\0FML3\0", 25577, 2), drain(channel.readInbound()));
        ZstdnetConnections.Info info = ZstdnetConnections.lookup(new InetSocketAddress("198.51.100.7", 40001));
        assertNotNull(info);
        assertEquals("mc.example.com\0FML3\0", info.rawHost());
        channel.finishAndReleaseAll();
    }

    @Test
    void realIpOffStillStripsAndRegistersUnderTheLoopbackAddress() {
        EmbeddedChannel channel = channelWith(settings(true, false, 256));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));

        ByteBuf forwarded = assertInstanceOf(ByteBuf.class, channel.readInbound());
        assertArrayEquals(cleanLogin(), drain(forwarded));
        assertNull(channel.readInbound()); // 没有 HAProxyMessage

        ZstdnetConnections.Info info = ZstdnetConnections.lookup(PEER);
        assertNotNull(info);
        assertEquals("203.0.113.9", info.realIp());
        assertTrue(reporter.outcomes.isEmpty());
        assertNotNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        channel.finishAndReleaseAll();
    }

    @Test
    void untrustedPeersDetachImmediatelyAndCannotSpoof() {
        EmbeddedChannel channel = channelWith(settings(false, true, 256));

        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
        byte[] wire = markedLogin("203.0.113.9");
        ByteBuf in = Unpooled.wrappedBuffer(wire);
        channel.writeInbound(in);

        ByteBuf out = channel.readInbound();
        assertSame(in, out); // 未经我们处理：原样、同一个对象
        assertArrayEquals(wire, drain(out));
        assertEquals(0, ZstdnetConnections.size());
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        channel.finishAndReleaseAll();
    }

    @Test
    void trustedConnectionsWithoutAMarkerPassThroughUntouched() {
        EmbeddedChannel login = channelWith(settings(true, true, 256));
        ByteBuf loginIn = Unpooled.wrappedBuffer(cleanLogin());
        login.writeInbound(loginIn);
        assertSame(loginIn, login.readInbound());
        loginIn.release();
        assertNull(login.readInbound());
        assertNull(login.pipeline().get(VelocityHandshakeSniffer.NAME));
        assertNull(login.pipeline().get(VelocityCompressionTuner.NAME));
        login.finishAndReleaseAll();

        EmbeddedChannel status = channelWith(settings(true, true, 256));
        ByteBuf statusIn = Unpooled.wrappedBuffer(handshake("mc.example.com", 25577, 1));
        status.writeInbound(statusIn);
        assertSame(statusIn, status.readInbound());
        statusIn.release();
        assertNull(status.pipeline().get(VelocityHandshakeSniffer.NAME));
        status.finishAndReleaseAll();

        assertEquals(0, ZstdnetConnections.size());
    }

    @Test
    void reassemblesAHandshakeSplitAcrossReads() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));
        byte[] wire = markedLogin("203.0.113.9");

        channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(wire, 0, 5)));
        assertNull(channel.readInbound());
        assertNotNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));

        channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(wire, 5, wire.length)));
        ((HAProxyMessage) channel.readInbound()).release();
        assertArrayEquals(cleanLogin(), drain(channel.readInbound()));
        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
        assertNotNull(ZstdnetConnections.lookup(new InetSocketAddress("203.0.113.9", 40001)));
        channel.finishAndReleaseAll();
    }

    @Test
    void passesThroughNonHandshakeFramesAfterReassemblingThem() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));
        // 帧长声明 2000 字节（仍在握手帧上限内，所以会攒着等），帧内容却不是握手：收全后原样放行。
        byte[] header = {(byte) 0xD0, 0x0F}; // varint 2000
        byte[] body = new byte[2000];
        Arrays.fill(body, (byte) 0x7F);
        channel.writeInbound(Unpooled.wrappedBuffer(header));
        channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 0, 1000)));
        assertNull(channel.readInbound());
        assertNotNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));

        channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 1000, body.length)));
        assertArrayEquals(ByteArrayOps.concat(header, body), drain(channel.readInbound()));
        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
        assertEquals(0, ZstdnetConnections.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void closingBeforeTheFirstFrameCompletesReleasesBufferedBytes() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));
        byte[] wire = markedLogin("203.0.113.9");
        channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(wire, 0, 10)));

        assertFalse(channel.finishAndReleaseAll()); // 未判完的半帧不会漏到下游
        assertEquals(0, ZstdnetConnections.size());
    }

    @Test
    void removesVelocitysHaproxyDecoderForZstdnetConnections() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256), new HAProxyMessageDecoder());
        assertNotNull(channel.pipeline().get(HAProxyMessageDecoder.class));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));

        assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
        HAProxyMessage proxy = assertInstanceOf(HAProxyMessage.class, channel.readInbound());
        assertEquals("203.0.113.9", proxy.sourceAddress());
        proxy.release();
        assertArrayEquals(cleanLogin(), drain(channel.readInbound()));
        channel.finishAndReleaseAll();
    }

    @Test
    void removesTheHaproxyDecoderEvenWhenRealIpRestoreIsOff() {
        EmbeddedChannel channel = channelWith(settings(true, false, 256), new HAProxyMessageDecoder());

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));

        assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
        assertArrayEquals(cleanLogin(), drain(channel.readInbound()));
        assertNotNull(ZstdnetConnections.lookup(PEER));
        channel.finishAndReleaseAll();
    }

    @Test
    void leavesGenuineProxyProtocolConnectionsToVelocity() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256), new HAProxyMessageDecoder());
        byte[] header = "PROXY TCP4 198.51.100.20 10.0.0.1 51234 25577\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] login = cleanLogin();

        channel.writeInbound(Unpooled.wrappedBuffer(ByteArrayOps.concat(header, login)));

        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
        HAProxyMessage proxy = assertInstanceOf(HAProxyMessage.class, channel.readInbound());
        assertEquals("198.51.100.20", proxy.sourceAddress()); // 由 Velocity 的解码器解出，不是我们发的
        proxy.release();
        assertArrayEquals(login, drain(channel.readInbound()));
        assertEquals(0, ZstdnetConnections.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void letsStatusPingsThroughTheProxyBypassTheHaproxyDecoder() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256), new HAProxyMessageDecoder());
        byte[] status = handshake("mc.example.com", 25577, 1);

        channel.writeInbound(Unpooled.wrappedBuffer(status));

        assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
        assertArrayEquals(status, drain(channel.readInbound()));
        assertEquals(0, ZstdnetConnections.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void verifiesThatVelocityPickedUpTheProxyMessage() {
        VelocityTestSupport.FakeConnection connection = new VelocityTestSupport.FakeConnection(PEER, true);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(VelocityHandshakeSniffer.CONNECTION_HANDLER, connection);
        channel.pipeline().addFirst(VelocityHandshakeSniffer.NAME, new VelocityHandshakeSniffer(settings(true, true, 256)));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));

        InetSocketAddress expected = new InetSocketAddress("203.0.113.9", 40001);
        assertEquals(expected, connection.remoteAddress());
        assertEquals(VelocityHandshakeSniffer.AddressOutcome.VERIFIED, reporter.outcomes.get(0));
        assertEquals(1, connection.received.size());
        assertArrayEquals(cleanLogin(), drain((ByteBuf) connection.received.get(0)));
        assertNotNull(ZstdnetConnections.lookup(connection.remoteAddress()));
        assertTrue(reporter.warnings.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test
    void fallsBackToPatchingTheAddressFieldWhenTheProxyMessageIsIgnored() {
        VelocityTestSupport.FakeConnection connection = new VelocityTestSupport.FakeConnection(PEER, false);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(VelocityHandshakeSniffer.CONNECTION_HANDLER, connection);
        channel.pipeline().addFirst(VelocityHandshakeSniffer.NAME, new VelocityHandshakeSniffer(settings(true, true, 256)));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));

        InetSocketAddress expected = new InetSocketAddress("203.0.113.9", 40001);
        assertEquals(expected, connection.remoteAddress());
        assertEquals(VelocityHandshakeSniffer.AddressOutcome.PATCHED, reporter.outcomes.get(0));
        assertNotNull(ZstdnetConnections.lookup(expected));
        drain((ByteBuf) connection.received.get(0));
        channel.finishAndReleaseAll();
    }

    @Test
    void restoresIpv6AddressesWithAMatchingRegistrationKey() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("2001:db8::1")));

        HAProxyMessage proxy = assertInstanceOf(HAProxyMessage.class, channel.readInbound());
        assertEquals(HAProxyProxiedProtocol.TCP6, proxy.proxiedProtocol());
        // Velocity 用 new InetSocketAddress(sourceAddress, sourcePort) 生成远端地址；登记键必须与之 equals。
        InetSocketAddress velocityView = new InetSocketAddress(proxy.sourceAddress(), proxy.sourcePort());
        proxy.release();
        assertEquals(new InetSocketAddress("2001:db8::1", 40001), velocityView);
        assertNotNull(ZstdnetConnections.lookup(velocityView));
        channel.finishAndReleaseAll();
    }

    @Test
    void invalidForwardedAddressIsStrippedButNotRestored() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));

        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("not-an-ip")));

        ByteBuf forwarded = assertInstanceOf(ByteBuf.class, channel.readInbound()); // 没有 HAProxyMessage
        assertArrayEquals(cleanLogin(), drain(forwarded));
        assertNotNull(ZstdnetConnections.lookup(PEER)); // 仍是 ZstdNet 连接
        assertTrue(reporter.warnings.contains("bad-address"));
        channel.finishAndReleaseAll();
    }

    @Test
    void noCompressionTunerWhenVelocityCompressionIsOff() {
        EmbeddedChannel off = channelWith(settings(true, true, -1));
        off.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));
        assertNull(off.pipeline().get(VelocityCompressionTuner.NAME));
        off.finishAndReleaseAll();

        EmbeddedChannel high = channelWith(settings(true, true, VelocityCompressionTuner.ZSTD_COMPRESSION_THRESHOLD));
        high.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));
        assertNull(high.pipeline().get(VelocityCompressionTuner.NAME));
        high.finishAndReleaseAll();
    }

    @Test
    void tunerInstalledBySnifferRaisesTheThresholdWhenVelocityEnablesCompression() {
        EmbeddedChannel channel = channelWith(settings(true, true, 256));
        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));
        ((HAProxyMessage) channel.readInbound()).release();
        drain(channel.readInbound());

        // 模拟 AuthSessionHandler：SetCompression 之后 setCompressionThreshold 装编码器并发事件。
        VelocityTestSupport.FakeCompressorEncoder encoder = new VelocityTestSupport.FakeCompressorEncoder(256);
        channel.pipeline().addLast(VelocityCompressionTuner.ENCODER_NAME, encoder);
        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);

        assertEquals(VelocityCompressionTuner.ZSTD_COMPRESSION_THRESHOLD, encoder.threshold());
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        channel.finishAndReleaseAll();
    }

    @Test
    void unregisterOnCloseDoesNotDropANewerRegistrationForTheSameKey() {
        EmbeddedChannel channel = channelWith(settings(true, false, 256));
        channel.writeInbound(Unpooled.wrappedBuffer(markedLogin("203.0.113.9")));
        drain(channel.readInbound());

        // 同一源端口被新连接复用并重新登记后，旧连接的关闭回调不能把新登记删掉。
        ZstdnetConnections.Info newer = new ZstdnetConnections.Info("198.51.100.1", "x", PROTOCOL, System.nanoTime());
        ZstdnetConnections.register(PEER, newer);
        channel.close();
        channel.runPendingTasks();

        assertSame(newer, ZstdnetConnections.lookup(PEER));
        assertFalse(channel.isOpen());
    }
}
