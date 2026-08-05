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

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * {@link RealIpForwarding} 的纯解析部分：从登录握手首帧里取出 zstdnet 的真实 IP 标记并把它剥掉。
 *
 * <p>本类刻意<b>不依赖 netty、不依赖 Bukkit</b>，只处理字节——因为它跑在每条连接的第一帧上，
 * 是整个功能里唯一「改动线上字节」的地方，必须能被单元测试逐字节覆盖
 * （见 {@code RealIpHandshakeTest}）。netty 管线接线、地址反射替换留在 {@code RealIpForwarding}。</p>
 *
 * <p>处理的是原版握手包的线格式，自 1.7 起未变过：</p>
 * <pre>[varint 帧长][varint 包 id=0][varint 协议版本][varint host 长][host][ushort 端口][varint next_state]</pre>
 */
final class RealIpHandshake {

    /** 与 {@code ServerProxyRuntime#appendForwardedIpMarker} / mod 端 hook 一致的标记前缀。 */
    static final String TOKEN_PREFIX = "zstdnet-real-ip=";
    /** 历史标记形式（{@code \0zstdnet-real-ip\0<ip>}），一并兼容。 */
    static final String LEGACY_MARKER = "\0zstdnet-real-ip\0";
    /** 原版握手帧上限：host 最长 255 字符，加上各 varint 也远不到这个数。 */
    static final int MAX_HANDSHAKE_FRAME = 2048;
    /** PROXY protocol v1 的 ASCII 前缀。 */
    private static final byte[] PROXY_V1_PREFIX = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    /** PROXY protocol v2 的 12 字节签名。 */
    private static final byte[] PROXY_V2_SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    private RealIpHandshake() {
    }

    /** 首帧判定结果。 */
    static final class Result {
        /** 标记里的真实 IP 字面量；{@code null} 表示首帧不是「带标记的登录握手」。 */
        final String realIp;
        /** 应当交给后端的字节：命中标记时是剥净标记后的重建结果，否则就是入参本身。 */
        final byte[] payload;

        private Result(String realIp, byte[] payload) {
            this.realIp = realIp;
            this.payload = payload;
        }
    }

    /**
     * 解析已收到的字节。
     *
     * @param data 连接上迄今收到的原始字节（可能不止首帧，后面往往紧跟着 login start）
     * @return 判定结果；{@code null} 表示首帧还没收全，需要更多字节
     */
    static Result parse(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        // 1.6 及更早的 legacy ping（0xFE ...）不是 varint 帧：会被误判成一个 254 字节的帧而一直等下去，
        // 直接放行交给 MC 自己的 legacy_query 处理器。
        if (data[0] == (byte) 0xFE) {
            return passthrough(data);
        }
        // 同理：服务端自己开了 PROXY protocol（Paper 的 proxy-protocol）时，首包是 PROXY 头而不是握手，
        // 其中 v1 的 'P'(0x50) 会被读成「81 字节的帧」而一直等。认出来就直接放行。
        if (startsWith(data, PROXY_V1_PREFIX) || startsWith(data, PROXY_V2_SIGNATURE)) {
            return passthrough(data);
        }

        VarIntRead frameLength = VarIntCodec.read(data, 0, data.length);
        if (frameLength == null) {
            // varint 尚未读全（不足 5 字节）才可能为 null；满 5 字节仍读不出就不是合法帧。
            return data.length >= 5 ? passthrough(data) : null;
        }
        if (frameLength.value() <= 0 || frameLength.value() > MAX_HANDSHAKE_FRAME) {
            return passthrough(data);
        }

        int frameEnd = frameLength.next() + frameLength.value();
        if (frameEnd > data.length) {
            return null; // 首帧还没收全。
        }

        VarIntRead packetId = VarIntCodec.read(data, frameLength.next(), frameEnd);
        if (packetId == null || packetId.value() != 0) {
            return passthrough(data);
        }
        VarIntRead protocol = VarIntCodec.read(data, packetId.next(), frameEnd);
        if (protocol == null) {
            return passthrough(data);
        }
        VarIntRead hostLength = VarIntCodec.read(data, protocol.next(), frameEnd);
        if (hostLength == null || hostLength.value() <= 0) {
            return passthrough(data);
        }

        int hostStart = hostLength.next();
        int hostEnd = hostStart + hostLength.value();
        if (hostEnd + 2 > frameEnd) {
            return passthrough(data);
        }
        VarIntRead nextState = VarIntCodec.read(data, hostEnd + 2, frameEnd);
        if (nextState == null || nextState.value() != 2) {
            return passthrough(data); // 只处理登录握手；状态查询不带标记，也不需要 IP。
        }

        String host = new String(data, hostStart, hostLength.value(), StandardCharsets.UTF_8);
        Marker marker = Marker.extract(host);
        if (marker == null) {
            return passthrough(data); // 没有标记：不是经本代理来的连接，原样放行。
        }

        byte[] hostBytes = marker.cleanedHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            return passthrough(data); // 理论到不了（剥标记只会变短），保险起见不动。
        }

        // 重建首帧：host 换成剥净的 cleanedHost，长度前缀按新长度重算（可能少一个字节），
        // 首帧之后的字节（往往是紧随其后的 login start）原样接在后面。
        byte[] payload = ByteArrayOps.concat(
            ByteArrayOps.slice(data, frameLength.next(), protocol.next()), // 包 id + 协议版本
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            ByteArrayOps.slice(data, hostEnd, frameEnd)                    // 端口 + next_state
        );
        byte[] rebuilt = ByteArrayOps.concat(
            VarIntCodec.encode(payload.length),
            payload,
            ByteArrayOps.slice(data, frameEnd, data.length)
        );
        return new Result(marker.ip, rebuilt);
    }

    private static Result passthrough(byte[] data) {
        return new Result(null, data);
    }

    /**
     * 前缀比对。字节还不够长时返回 false —— 此时交给下面的正常解析，它会因「首帧未收全」而继续等，
     * 等够了再判。绝不能在字节不足时就认定是 PROXY 头，否则首字节恰为 {@code 'P'}（帧长 80，很常见）的
     * 真握手会被误放行、丢掉真实 IP 还原。
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 从握手 host 中拆出的真实 IP 与剥净标记后的 host。 */
    private static final class Marker {
        private final String ip;
        private final String cleanedHost;

        private Marker(String ip, String cleanedHost) {
            this.ip = ip;
            this.cleanedHost = cleanedHost;
        }

        /** @return 解析结果；host 里没有标记时返回 null */
        static Marker extract(String host) {
            String[] parts = host.split("\0", -1);
            StringBuilder cleaned = new StringBuilder(host.length());
            String ip = null;
            boolean first = true;
            for (String part : parts) {
                if (part.startsWith(TOKEN_PREFIX)) {
                    String encoded = part.substring(TOKEN_PREFIX.length()).trim();
                    if (!encoded.isEmpty() && encoded.length() <= 96) {
                        try {
                            ip = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                        } catch (IllegalArgumentException ignored) {
                            // 标记损坏：当作没解析出 IP，但仍然剥掉这一段。
                        }
                    }
                    continue; // 无论解码成不成功都剥掉，别泄漏给后端。
                }
                if (!first) {
                    cleaned.append('\0');
                }
                cleaned.append(part);
                first = false;
            }
            if (ip != null && !ip.trim().isEmpty()) {
                return new Marker(ip.trim(), cleaned.toString());
            }

            // 历史形式：host + "\0zstdnet-real-ip\0" + ip
            int legacy = host.lastIndexOf(LEGACY_MARKER);
            if (legacy >= 0) {
                String value = host.substring(legacy + LEGACY_MARKER.length()).trim();
                if (!value.isEmpty() && value.indexOf('\0') < 0 && value.length() <= 64) {
                    return new Marker(value, host.substring(0, legacy));
                }
            }
            return null;
        }
    }

    /**
     * 只接受 IP 字面量，绝不触发 DNS 解析（调用方跑在 netty 事件循环上，阻塞不得）。
     * <p>IPv4 自行解析成 4 字节走 {@code getByAddress}；含 {@code :} 的交给 {@code getByName}——
     * JDK 对含冒号的主机名只做 IPv6 字面量解析，失败直接抛异常，不会走 DNS。
     *
     * @return 解析出的地址；不是合法字面量时返回 null
     */
    static InetAddress parseIpLiteral(String text) {
        if (text == null || text.isEmpty() || text.length() > 64) {
            return null;
        }
        try {
            if (text.indexOf(':') >= 0) {
                return InetAddress.getByName(text);
            }
            String[] octets = text.split("\\.", -1);
            if (octets.length != 4) {
                return null;
            }
            byte[] raw = new byte[4];
            for (int i = 0; i < 4; i++) {
                String octet = octets[i];
                if (octet.isEmpty() || octet.length() > 3) {
                    return null;
                }
                int value = 0;
                for (int j = 0; j < octet.length(); j++) {
                    char c = octet.charAt(j);
                    if (c < '0' || c > '9') {
                        return null;
                    }
                    value = value * 10 + (c - '0');
                }
                if (value > 255) {
                    return null;
                }
                raw[i] = (byte) value;
            }
            return InetAddress.getByAddress(raw);
        } catch (Throwable t) {
            return null;
        }
    }
}
