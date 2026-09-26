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
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件端真实 IP 还原的线格式测试：这是唯一会改动线上字节的一段逻辑，逐字节覆盖。
 * <p>前半部分与 Bukkit 插件的同名测试逐条一致（两边解析行为必须相同）；末尾是 Velocity 端新增字段的断言。
 */
class RealIpHandshakeTest {

    /** 用 String.valueOf 造 NUL：字面量里的 \0 若后接数字会被当成八进制转义（"\0203" = 0x10 + "3"）。 */
    private static final String NUL = String.valueOf((char) 0);

    private static byte[] handshake(String host, int port, int nextState) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        byte[] payload = ByteArrayOps.concat(
            VarIntCodec.encode(0),                       // packet id
            VarIntCodec.encode(763),                     // protocol version
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            new byte[] {(byte) (port >> 8), (byte) port},
            VarIntCodec.encode(nextState)
        );
        return ByteArrayOps.concat(VarIntCodec.encode(payload.length), payload);
    }

    private static String marker(String ip) {
        return "\0zstdnet-real-ip="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(ip.getBytes(StandardCharsets.UTF_8));
    }

    /** 把重建出来的首帧再解回 host，验证改写后仍是合法握手。 */
    private static String hostOf(byte[] frame) {
        VarIntRead length = VarIntCodec.read(frame, 0, frame.length);
        assertNotNull(length);
        int frameEnd = length.next() + length.value();
        VarIntRead packetId = VarIntCodec.read(frame, length.next(), frameEnd);
        assertNotNull(packetId);
        assertEquals(0, packetId.value());
        VarIntRead protocol = VarIntCodec.read(frame, packetId.next(), frameEnd);
        assertNotNull(protocol);
        assertEquals(763, protocol.value());
        VarIntRead hostLength = VarIntCodec.read(frame, protocol.next(), frameEnd);
        assertNotNull(hostLength);
        return new String(frame, hostLength.next(), hostLength.value(), StandardCharsets.UTF_8);
    }

    @Test
    void extractsRealIpAndStripsTheMarker() {
        byte[] wire = handshake("mc.example.com" + marker("203.0.113.9"), 25565, 2);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertEquals("203.0.113.9", result.realIp);
        assertEquals("mc.example.com", hostOf(result.payload));
        // 剥掉标记后整帧必须变短，且长度前缀已按新长度重算。
        assertTrue(result.payload.length < wire.length);
        assertArrayEquals(handshake("mc.example.com", 25565, 2), result.payload);
    }

    @Test
    void keepsForgeStyleHostSegmentsIntact() {
        // Forge 客户端的 host 形如 "mc.example.com\0FML3\0"（末尾带空段），代理会再追加自己那一段。
        // 剥完之后必须逐字符还原成 Forge 原本的样子，末尾的空段也不能丢。
        byte[] wire = handshake("mc.example.com\0FML3\0" + marker("198.51.100.7"), 25565, 2);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertEquals("198.51.100.7", result.realIp);
        assertEquals("mc.example.com\0FML3\0", hostOf(result.payload));
    }

    @Test
    void supportsTheLegacyMarkerForm() {
        byte[] wire = handshake("mc.example.com" + NUL + "zstdnet-real-ip" + NUL + "203.0.113.9", 25565, 2);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertEquals("203.0.113.9", result.realIp);
        assertEquals("mc.example.com", hostOf(result.payload));
    }

    @Test
    void passesThroughHandshakesWithoutAMarker() {
        byte[] wire = handshake("mc.example.com", 25565, 2);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertNull(result.realIp);
        // 无标记时必须原样（同一个数组）放行，绝不重建。
        assertSame(wire, result.payload);
    }

    @Test
    void ignoresStatusHandshakes() {
        byte[] wire = handshake("mc.example.com" + marker("203.0.113.9"), 25565, 1);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertNull(result.realIp);
        assertSame(wire, result.payload);
    }

    @Test
    void keepsTrailingBytesAfterTheFirstFrame() {
        // 握手与 login start 常常挤在同一个 TCP 段里：首帧之后的字节必须原样接在后面。
        byte[] loginStart = {0x05, 0x00, 0x03, 'a', 'b', 'c'};
        byte[] wire = ByteArrayOps.concat(handshake("mc.example.com" + marker("203.0.113.9"), 25565, 2), loginStart);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertEquals("203.0.113.9", result.realIp);
        byte[] tail = Arrays.copyOfRange(result.payload, result.payload.length - loginStart.length, result.payload.length);
        assertArrayEquals(loginStart, tail);
    }

    @Test
    void asksForMoreBytesUntilTheFirstFrameIsComplete() {
        byte[] wire = handshake("mc.example.com" + marker("203.0.113.9"), 25565, 2);

        assertNull(RealIpHandshake.parse(Arrays.copyOf(wire, 1)));
        assertNull(RealIpHandshake.parse(Arrays.copyOf(wire, wire.length - 1)));

        RealIpHandshake.Result whole = RealIpHandshake.parse(wire);
        assertNotNull(whole);
        assertEquals("203.0.113.9", whole.realIp);
    }

    @Test
    void passesThroughLegacyPingInsteadOfWaitingForever() {
        // 0xFE 开头的 1.6 legacy ping 会被 varint 误读成一个 254 字节的帧；必须立即放行而不是一直等。
        byte[] legacy = {(byte) 0xFE, 0x01};

        RealIpHandshake.Result result = RealIpHandshake.parse(legacy);

        assertNotNull(result);
        assertNull(result.realIp);
        assertSame(legacy, result.payload);
    }

    @Test
    void passesThroughProxyProtocolHeaders() {
        // 服务端自己开了 PROXY protocol 时首包不是握手。v1 的 'P'=0x50 会被读成「81 字节的帧」，
        // 必须认出来放行而不是一直等；v2 的签名同理。
        byte[] v1 = "PROXY TCP4 203.0.113.9 10.0.0.1 51234 25565\r\n".getBytes(StandardCharsets.US_ASCII);
        RealIpHandshake.Result r1 = RealIpHandshake.parse(v1);
        assertNotNull(r1);
        assertNull(r1.realIp);
        assertSame(v1, r1.payload);

        byte[] v2 = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A, 0x21, 0x11};
        RealIpHandshake.Result r2 = RealIpHandshake.parse(v2);
        assertNotNull(r2);
        assertNull(r2.realIp);
        assertSame(v2, r2.payload);
    }

    @Test
    void stillRestoresWhenTheFrameLengthLooksLikeAProxyPrefix() {
        // 帧长恰好是 0x50（'P'）的真握手不能被 PROXY v1 前缀判定误伤：填充 host 直到首字节命中。
        byte[] wire = null;
        for (int pad = 0; pad < 128 && wire == null; pad++) {
            StringBuilder host = new StringBuilder("mc.example.com");
            for (int i = 0; i < pad; i++) {
                host.append('x');
            }
            byte[] candidate = handshake(host + marker("203.0.113.9"), 25565, 2);
            if (candidate[0] == (byte) 'P') {
                wire = candidate;
            }
        }
        assertNotNull(wire, "should have found a handshake whose length prefix is 0x50");

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertEquals("203.0.113.9", result.realIp);
    }

    @Test
    void passesThroughWhenTheMarkerIsCorrupt() {
        byte[] wire = handshake("mc.example.com\0zstdnet-real-ip=!!not-base64!!", 25565, 2);

        RealIpHandshake.Result result = RealIpHandshake.parse(wire);

        assertNotNull(result);
        assertNull(result.realIp);
        assertSame(wire, result.payload);
    }

    @Test
    void parsesIpLiteralsWithoutTouchingDns() {
        assertEquals("203.0.113.9", RealIpHandshake.parseIpLiteral("203.0.113.9").getHostAddress());

        InetAddress v6 = RealIpHandshake.parseIpLiteral("2001:db8::1");
        assertNotNull(v6);
        assertEquals(16, v6.getAddress().length);

        // 主机名 / 越界八位组一律拒绝——否则会在 netty 事件循环上触发 DNS 解析。
        assertNull(RealIpHandshake.parseIpLiteral("example.com"));
        assertNull(RealIpHandshake.parseIpLiteral("999.1.1.1"));
        assertNull(RealIpHandshake.parseIpLiteral("203.0.113"));
        assertNull(RealIpHandshake.parseIpLiteral(""));
        assertNull(RealIpHandshake.parseIpLiteral(null));
    }

    // ------------------------------------------------------------------
    // Velocity 端新增：Result 上的 host / protocol / nextState / proxyHeader
    // ------------------------------------------------------------------

    @Test
    void exposesCleanedHostProtocolAndNextStateForMarkedLogins() {
        RealIpHandshake.Result result = RealIpHandshake.parse(handshake("mc.example.com\0FML3\0" + marker("198.51.100.7"), 25565, 2));

        assertNotNull(result);
        assertEquals("mc.example.com\0FML3\0", result.host);
        assertEquals(763, result.protocol);
        assertEquals(2, result.nextState);
        assertFalse(result.proxyHeader);
    }

    @Test
    void reportsNextStateForUnmarkedHandshakes() {
        RealIpHandshake.Result status = RealIpHandshake.parse(handshake("mc.example.com", 25565, 1));
        assertNotNull(status);
        assertNull(status.realIp);
        assertNull(status.host);
        assertEquals(1, status.nextState);
        assertEquals(763, status.protocol);

        RealIpHandshake.Result login = RealIpHandshake.parse(handshake("mc.example.com", 25565, 2));
        assertNotNull(login);
        assertNull(login.realIp);
        assertEquals(2, login.nextState);
    }

    @Test
    void flagsProxyProtocolHeaders() {
        RealIpHandshake.Result v1 = RealIpHandshake.parse("PROXY TCP4 203.0.113.9 10.0.0.1 51234 25565\r\n".getBytes(StandardCharsets.US_ASCII));
        assertNotNull(v1);
        assertTrue(v1.proxyHeader);
        assertEquals(-1, v1.nextState);

        RealIpHandshake.Result legacy = RealIpHandshake.parse(new byte[] {(byte) 0xFE, 0x01});
        assertNotNull(legacy);
        assertFalse(legacy.proxyHeader);
        assertEquals(-1, legacy.nextState);
    }

    @Test
    void neverResolvesHostnamesThatContainAColon() {
        // 首字符不是十六进制数字 / ':' 的「含冒号主机名」JDK 会拿去做 DNS 解析，必须直接拒绝。
        assertNull(RealIpHandshake.parseIpLiteral("zz:zz"));
        assertNull(RealIpHandshake.parseIpLiteral("example.com:80"));
        assertNotNull(RealIpHandshake.parseIpLiteral("::1"));
        assertNotNull(RealIpHandshake.parseIpLiteral("::ffff:203.0.113.9"));
    }
}
