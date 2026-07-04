/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.proxy;

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.compress.ZstdStreams;
import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 「对端会不会说 ZSTD」探测（raw_fallback 回退兼容的判据）。
 *
 * <p>动机：樱花frp 等联机映射常把「开放到局域网」的<b>原版</b>端口直接映射到公网。此时目标地址
 * 是公网域名（不落在 {@link ConnectTargets#isDirectLanTarget} 的局域网直连快路径里），
 * 客户端会强制走 ZSTD 代理，而端口后面并没有 ZstdNet 服务端，登录必然失败（误报「未装 zstd」）。
 * 不能用原版 status ping 区分——ZstdNet 服务端也会透传原版 status ping（服务器列表可见 MOTD），
 * 所以反过来发一次 <b>zstd 压缩的 status 请求</b>：只有 ZstdNet 服务端能解出来并回出合法的
 * <b>zstd 压缩</b> status 响应；原版端口把压缩字节当垃圾（断开或沉默）。</p>
 *
 * <p>结果四态（调用方仅在 {@link Result#NO_ZSTD} 时回退原版直连，其余一律维持 ZSTD 代理；
 * 见各变体 ClientProxyPublisher / ConnectScreenHooks）：</p>
 * <ul>
 *     <li>{@link Result#ZSTD}：对端回出了合法的压缩 status 响应 → 照常走 ZSTD 代理；</li>
 *     <li>{@link Result#NO_ZSTD}：TCP 通了、且拿到<b>确凿</b>的负面信号（对端断开 / 复位 /
 *         回了非法压缩字节）→ 断定对端不说 ZSTD，回退原版直连；</li>
 *     <li>{@link Result#AMBIGUOUS}：TCP 通了但限时内<b>沉默</b>（读超时）→ 无法断定：真实
 *         ZstdNet 服务端跨区/高延迟/GC 卡顿也会这样。此时<b>不</b>回退——否则会把一个能连的
 *         ZSTD-only 服务端错误地用原版直连过去、被判 RAW_LOGIN 直接踢下线（弄巧成拙）。
 *         维持 ZSTD 代理路径，沿用既有登录期友好报错；</li>
 *     <li>{@link Result#UNREACHABLE}：TCP 都连不上（离线 / 地址错）→ 保持 ZSTD 代理路径，
 *         让既有的登录期友好报错（DISCONNECT_UNREACHABLE 等）继续生效。</li>
 * </ul>
 */
public final class ZstdProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZstdProbe.class);

    /** TCP 连接超时：探测在点击「加入服务器」的渲染线程上同步执行，须给出可忍受的上限。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2500;
    /**
     * 响应读超时：status 往返极轻（1~2 RTT）。超时不再等同「不说 ZSTD」——它只判 {@link Result#AMBIGUOUS}
     * 并维持 ZSTD 路径，所以这里取较短值（1.5s）以压低点击加入时的主线程卡顿；真实的原版端口靠对端
     * <b>主动断开</b>（非超时）被判 NO_ZSTD，不依赖这个时限。
     */
    public static final int DEFAULT_READ_TIMEOUT_MS = 1500;

    /** 探测响应包的解压后长度上限（status JSON 带 favicon 可到几百 KB，8 MiB 足够且防恶意声明）。 */
    private static final int MAX_STATUS_RESPONSE_BYTES = 8 * 1024 * 1024;

    /**
     * 握手 host 的防误判填充（跟在 {@code \0} 标记后，原版/后端对 status 握手的 host 内容不敏感）。
     * <p>为什么需要：ZstdNet 服务端的入口探测（detectClientMode）把首字节当原版包长 varint 读——
     * zstd 帧魔数首字节 0x28 会被误读成「40 字节的包」，若我们首次 flush 的压缩字节不足 41 字节，
     * 服务端会一直等后续字节直到我方超时，把真 ZSTD 服务端误判成 NO_ZSTD。真实客户端随后还有
     * login-start 等数据所以不受影响；探测则必须自己把首次 flush 撑过这个门槛。填充选用无重复
     * 模式的固定串（避免被 zstd RLE 压没）。</p>
     */
    private static final String HOST_PADDING = "\0zstdprobe:kQ3vZx8pT1mW6rJ4bN9cD2fH7sLgYaEuVoR5iA0q";

    public enum Result {
        /** 对端回出合法的 zstd 压缩 status 响应：确认在说 ZSTD。 */
        ZSTD,
        /** TCP 通、且拿到确凿负面信号（对端主动断开 / 复位 / 回了非法压缩字节）：断定不说 ZSTD。 */
        NO_ZSTD,
        /** TCP 通但限时内沉默（读超时）：无法断定，按不确定处理（维持 ZSTD，绝不据此回退）。 */
        AMBIGUOUS,
        /** TCP 连接失败（离线 / 拒绝 / 地址错）。 */
        UNREACHABLE
    }

    private ZstdProbe() {
    }

    /** 用默认超时探测，见 {@link #probe(String, int, int, int)}。 */
    public static Result probe(String host, int port) {
        return probe(host, port, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * 向 {@code host:port} 发一次 zstd 压缩的 status 请求并验证响应。
     *
     * @param host 已解析的连接主机（与真正建连的目标一致）
     * @param port 目标端口
     */
    public static Result probe(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        try {
            AndroidZstdNativeLoader.prepare(LOGGER);
        } catch (Throwable ignored) {
        }

        try (Socket probe = new Socket()) {
            try {
                probe.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            } catch (Exception connectErr) {
                LOGGER.info("zstdnet: probe {}:{} unreachable: {}", host, port, connectErr.toString());
                return Result.UNREACHABLE;
            }

            probe.setTcpNoDelay(true);
            probe.setSoTimeout(readTimeoutMs);

            // 压缩的 status 握手 + status 请求。协议号对 status 探测不敏感，固定用 763。
            OutputStream zstdOut = ZstdStreams.newCompressor(
                probe.getOutputStream(), 3, CompressionOptions.none(), false);
            byte[] hostBytes = (host + HOST_PADDING).getBytes(StandardCharsets.UTF_8);
            byte[] handshakePayload = ByteArrayOps.concat(
                VarIntCodec.encode(0),
                VarIntCodec.encode(763),
                VarIntCodec.encode(hostBytes.length),
                hostBytes,
                new byte[]{(byte) (port >>> 8), (byte) port},
                VarIntCodec.encode(1)
            );
            PacketIo.writePacket(zstdOut, handshakePayload);
            PacketIo.writePacket(zstdOut, VarIntCodec.encode(0));
            zstdOut.flush();

            InputStream zstdIn = ZstdStreams.newDecompressor(
                probe.getInputStream(), CompressionOptions.none(), null);
            byte[] response = PacketIo.readPacket(zstdIn, MAX_STATUS_RESPONSE_BYTES);
            boolean ok = isStatusResponse(response);
            LOGGER.info("zstdnet: probe {}:{} -> {}", host, port, ok ? "ZSTD" : "NO_ZSTD (invalid response)");
            return ok ? Result.ZSTD : Result.NO_ZSTD;
        } catch (Exception ex) {
            if (isTimeout(ex)) {
                // 已建连但限时内没读到任何合法压缩响应：无法断定对端「不说 ZSTD」。真实 ZstdNet
                // 服务端跨区/高延迟/GC 卡顿也会读超时；若据此回退原版直连，到 ZSTD-only 服务端会被
                // 判 RAW_LOGIN 直接踢下线（比原来还糟）。故超时判 AMBIGUOUS，维持 ZSTD 代理路径。
                LOGGER.info("zstdnet: probe {}:{} -> AMBIGUOUS (timed out waiting for response): {}", host, port, ex.toString());
                return Result.AMBIGUOUS;
            }
            // 已建连后拿到确凿负面信号（对端断开 / 复位 / 解压垃圾）：断定对端不说 ZSTD。
            LOGGER.info("zstdnet: probe {}:{} -> NO_ZSTD: {}", host, port, ex.toString());
            return Result.NO_ZSTD;
        }
    }

    /** 异常链上是否有 socket 读超时（可能被 zstd-jni 的解压 InputStream 包过一层）。 */
    private static boolean isTimeout(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SocketTimeoutException) {
                return true;
            }
            if (cur == cur.getCause()) {
                break;
            }
        }
        return false;
    }

    /** 校验解压后的首包形如原版 status 响应：包 id 0 + 合法的 JSON 字符串长度。纯函数，便于单测。 */
    static boolean isStatusResponse(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return false;
        }
        VarIntRead packetId = VarIntCodec.read(packet, 0);
        if (packetId == null || packetId.value() != 0) {
            return false;
        }
        VarIntRead jsonLength = VarIntCodec.read(packet, packetId.next());
        if (jsonLength == null || jsonLength.value() < 0) {
            return false;
        }
        return jsonLength.next() + jsonLength.value() <= packet.length;
    }
}
