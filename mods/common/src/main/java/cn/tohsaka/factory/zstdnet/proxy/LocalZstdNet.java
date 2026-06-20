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
import cn.tohsaka.factory.zstdnet.core.io.StreamTransfer;
import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import cn.tohsaka.factory.zstdnet.core.protocol.VoiceTunnelFrame;
import cn.tohsaka.factory.zstdnet.core.cache.CacheStats;
import cn.tohsaka.factory.zstdnet.core.cache.CacheUntransformingInputStream;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheFormat;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheHandshake;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheStore;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkManifest;
import cn.tohsaka.factory.zstdnet.core.cache.Hash128;
import cn.tohsaka.factory.zstdnet.core.transform.TransformHandshake;
import cn.tohsaka.factory.zstdnet.core.transform.TransformOptions;
import cn.tohsaka.factory.zstdnet.core.transform.UntransformingInputStream;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalZstdNet {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalZstdNet.class);
    private static final int UDP_BUF_SIZE = 65535;
    private static final AtomicInteger WORKER_SEQ = new AtomicInteger(1);
    private static final AtomicInteger ACCEPT_SEQ = new AtomicInteger(1);
    private static final AtomicInteger UDP_SEQ = new AtomicInteger(1);
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zstdnet-worker-" + WORKER_SEQ.getAndIncrement());
        t.setDaemon(true);
        // 后台压缩/转发线程降低优先级，CPU 紧张时让位给客户端渲染线程（不改变压缩 level）。
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return t;
    });

    // 客户端变换偏好（实体包流去交错）。默认关闭=不 advertise、不安装逆向包装，与历史逐字节一致、零开销。
    // 全局而非逐连接：一个游戏实例只有一份客户端配置。各变体在客户端配置(重)加载时调用 configureClientTransform。
    private static volatile TransformOptions clientTransform = TransformOptions.disabled();

    // 客户端区块引用缓存（CRC）偏好。默认开启=advertise ccache 并安装逆向包装——“自动默认、协商后自动启用”。
    // 仅在服务端也启用并实际下发 CRC 流（ZNCR 魔数）时才生效；否则逆向流自检 MAGIC 不符即原样透传，与历史逐字节一致。
    private static volatile boolean clientCacheEnabled = true;
    // 跨会话持久化（v2）：默认开启=把整发区块落盘到 config/zstdnet-cache/<server>/，重连时发 manifest 让服务端
    // 对已持有区块发 WARM_REF（跨会话省传）。关掉只退化为会话内 REF（仍线兼容、仍 fail-closed）。
    private static volatile boolean clientCachePersist = true;
    private static volatile long clientCachePersistBytes = ChunkCacheFormat.DEFAULT_PERSIST_BYTES;

    public enum Mode {
        AUTO,
        RAW,
        ZSTD
    }

    private LocalZstdNet() {
    }

    /** 设置客户端变换偏好（由各变体在客户端配置加载时调用）；null 视为关闭。 */
    public static void configureClientTransform(TransformOptions options) {
        clientTransform = options == null ? TransformOptions.disabled() : options;
    }

    /** 设置客户端区块引用缓存偏好（由各变体在客户端配置加载时调用）；默认开启。 */
    public static void configureClientCache(boolean enabled) {
        clientCacheEnabled = enabled;
    }

    /**
     * 设置客户端区块引用缓存偏好 + 跨会话持久化（由各变体在客户端配置加载时调用）。
     *
     * @param enabled     是否启用 CRC（advertise + 安装逆向包装）
     * @param persist     是否跨会话落盘持久化（关掉则仅会话内 REF）
     * @param persistBytes 持久缓存字节预算（磁盘 + 内存 warm 共用；&lt;=0 用默认值）
     */
    public static void configureClientCache(boolean enabled, boolean persist, long persistBytes) {
        clientCacheEnabled = enabled;
        clientCachePersist = persist;
        clientCachePersistBytes = persistBytes > 0 ? persistBytes : ChunkCacheFormat.DEFAULT_PERSIST_BYTES;
    }

    public static ProxyHandle start(String remoteHost, int remotePort, int level, Mode requestedMode) throws IOException {
        return start(remoteHost, remotePort, remoteHost, remotePort, remoteHost, remotePort, level, CompressionOptions.none(), requestedMode);
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String presentedHost,
        int presentedPort,
        int level,
        Mode requestedMode
    ) throws IOException {
        return start(remoteHost, remotePort, remoteHost, remotePort, presentedHost, presentedPort, level, CompressionOptions.none(), requestedMode);
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        Mode requestedMode
    ) throws IOException {
        return start(remoteHost, remotePort, statusHost, statusPort, presentedHost, presentedPort, level, CompressionOptions.none(), requestedMode);
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        CompressionOptions compression,
        Mode requestedMode
    ) throws IOException {
        CompressionOptions options = compression == null ? CompressionOptions.none() : compression;
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));

        Mode resolvedMode = resolveMode(remoteHost, remotePort, requestedMode);
        LOGGER.info(
            "zstdnet: TCP local proxy armed {} -> {}:{} (status {}:{}, presented {}:{}, mode {})",
            listener.getLocalSocketAddress(),
            remoteHost,
            remotePort,
            statusHost,
            statusPort,
            presentedHost,
            presentedPort,
            resolvedMode
        );
        AtomicBoolean running = new AtomicBoolean(true);
        ProxyStats stats = new ProxyStats();
        Thread acceptThread = new Thread(
            () -> acceptLoop(listener, running, remoteHost, remotePort, statusHost, statusPort, presentedHost, presentedPort, level, options, resolvedMode, stats),
            "zstdnet-accept-" + ACCEPT_SEQ.getAndIncrement() + "-" + remoteHost + ":" + remotePort
        );
        acceptThread.setDaemon(true);
        acceptThread.start();

        UdpForwarder udpForwarder = startUdpForwarder(remoteHost, remotePort, listener.getLocalPort());

        return new ProxyHandle(listener, running, acceptThread, udpForwarder, resolvedMode, remoteHost, remotePort, stats);
    }

    private static UdpForwarder startUdpForwarder(String remoteHost, int remotePort, int localPort) {
        try {
            UdpForwarder forwarder = new UdpForwarder(remoteHost, remotePort, localPort);
            forwarder.start();
            LOGGER.info("zstdnet: UDP passthrough armed 127.0.0.1:{} -> {}:{}", localPort, remoteHost, remotePort);
            return forwarder;
        } catch (IOException e) {
            LOGGER.warn("zstdnet: UDP passthrough disabled on 127.0.0.1:{} -> {}:{}: {}", localPort, remoteHost, remotePort, e.toString());
            return null;
        }
    }

    private static Mode resolveMode(String remoteHost, int remotePort, Mode requestedMode) {
        if (requestedMode == null || requestedMode == Mode.AUTO) {
            Mode picked = probeRawStatus(remoteHost, remotePort) ? Mode.RAW : Mode.ZSTD;
            LOGGER.info("zstdnet: auto mode {}:{} -> {}", remoteHost, remotePort, picked);
            return picked;
        }
        return requestedMode;
    }

    private static boolean probeRawStatus(String remoteHost, int remotePort) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(remoteHost, remotePort), 1500);
            probe.setSoTimeout(1500);

            byte[] hostBytes = remoteHost.getBytes(StandardCharsets.UTF_8);
            byte[] handshakePayload = ByteArrayOps.concat(
                VarIntCodec.encode(0),
                VarIntCodec.encode(763),
                VarIntCodec.encode(hostBytes.length),
                hostBytes,
                new byte[]{(byte) (remotePort >>> 8), (byte) remotePort},
                VarIntCodec.encode(1)
            );

            PacketIo.writePacket(probe.getOutputStream(), handshakePayload);
            PacketIo.writePacket(probe.getOutputStream(), VarIntCodec.encode(0));

            byte[] firstPacket = PacketIo.readPacket(probe.getInputStream());
            if (firstPacket.length == 0) {
                return false;
            }

            VarIntRead packetId = VarIntCodec.read(firstPacket, 0);
            if (packetId == null || packetId.value() != 0) {
                return false;
            }

            VarIntRead jsonLength = VarIntCodec.read(firstPacket, packetId.next());
            if (jsonLength == null || jsonLength.value() < 0) {
                return false;
            }

            return jsonLength.next() + jsonLength.value() <= firstPacket.length;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void acceptLoop(
        ServerSocket listener,
        AtomicBoolean running,
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        CompressionOptions compression,
        Mode mode,
        ProxyStats stats
    ) {
        LOGGER.info(
            "zstdnet: TCP accept loop waiting on {} for {}:{} (mode {})",
            listener.getLocalSocketAddress(),
            remoteHost,
            remotePort,
            mode
        );
        while (running.get()) {
            try {
                Socket localClient = listener.accept();
                LOGGER.info(
                    "zstdnet: TCP accepted local client {} -> {} for {}:{}",
                    localClient.getRemoteSocketAddress(),
                    localClient.getLocalSocketAddress(),
                    remoteHost,
                    remotePort
                );
                WORKERS.execute(() -> handleConnection(
                    localClient,
                    remoteHost,
                    remotePort,
                    statusHost,
                    statusPort,
                    presentedHost,
                    presentedPort,
                    level,
                    compression,
                    mode,
                    stats
                ));
            } catch (SocketException e) {
                if (running.get()) {
                    LOGGER.warn("zstdnet: accept failed: {}", e.toString());
                }
                return;
            } catch (Exception e) {
                LOGGER.warn("zstdnet: accept failed: {}", e.toString());
            }
        }
    }

    private static void handleConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        CompressionOptions compression,
        Mode mode,
        ProxyStats stats
    ) {
        try {
            localClient.setTcpNoDelay(true);
            localClient.setSoTimeout(5000);
            LOGGER.info(
                "zstdnet: waiting for Minecraft handshake from {} for {}:{}",
                localClient.getRemoteSocketAddress(),
                remoteHost,
                remotePort
            );
            byte[] handshake = readPacketWithRetry(localClient.getInputStream(), 5000);
            if (handshake.length == 0) {
                LOGGER.warn("zstdnet: handshake timeout from {} for {}:{}", localClient.getRemoteSocketAddress(), remoteHost, remotePort);
                closeQuietly(localClient);
                return;
            }

            localClient.setSoTimeout(0);
            byte[] rewrittenHandshake = rewriteHandshakeDestination(handshake, presentedHost, presentedPort);
            Integer nextState = extractHandshakeNextState(handshake);
            boolean isStatus = nextState != null && nextState == 1;
            LOGGER.info(
                "zstdnet: Minecraft handshake received from {} for {}:{} nextState={} mode={} status={} firstPacketBytes={}",
                localClient.getRemoteSocketAddress(),
                remoteHost,
                remotePort,
                nextState,
                mode,
                isStatus,
                handshake.length
            );

            if (isStatus) {
                handleRawConnection(localClient, statusHost, statusPort, rewrittenHandshake, stats);
                return;
            }

            if (mode == Mode.RAW) {
                handleRawConnection(localClient, remoteHost, remotePort, rewrittenHandshake, stats);
            } else {
                handleZstdConnection(localClient, remoteHost, remotePort, level, compression, rewrittenHandshake, stats);
            }
        } catch (Exception e) {
            LOGGER.warn(
                "zstdnet: local proxy failed for {} while targeting {}:{}: {}",
                localClient.getRemoteSocketAddress(),
                remoteHost,
                remotePort,
                e.toString(),
                e
            );
            closeQuietly(localClient);
        }
    }

    private static void handleRawConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        byte[] firstPacket,
        ProxyStats stats
    ) throws Exception {
        try (Socket client = localClient; Socket upstream = new Socket()) {
            LOGGER.info(
                "zstdnet: opening RAW upstream {} -> {}:{}",
                client.getRemoteSocketAddress(),
                remoteHost,
                remotePort
            );
            upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);
            LOGGER.info(
                "zstdnet: RAW upstream connected local {} -> remote {}",
                upstream.getLocalSocketAddress(),
                upstream.getRemoteSocketAddress()
            );

            OutputStream upstreamOut = new CountingOutputStream(upstream.getOutputStream(), stats::addClientToServerRawPassthrough);
            PacketIo.writePacket(upstreamOut, firstPacket);
            upstreamOut.flush();

            Future<?> upstreamWriter = WORKERS.submit(() -> {
                try {
                    StreamTransfer.copyAndFlush(client.getInputStream(), upstreamOut);
                } catch (Exception ignored) {
                } finally {
                    try {
                        upstream.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            Future<?> downstreamWriter = WORKERS.submit(() -> {
                try {
                    StreamTransfer.copyAndFlush(
                        new CountingInputStream(upstream.getInputStream(), stats::addServerToClientRawPassthrough),
                        client.getOutputStream()
                    );
                } catch (Exception ignored) {
                } finally {
                    try {
                        client.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            upstreamWriter.get();
            downstreamWriter.get();
        }
    }

    private static void handleZstdConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        int level,
        CompressionOptions compression,
        byte[] firstPacket,
        ProxyStats stats
    ) throws Exception {
        AndroidZstdNativeLoader.prepare(LOGGER);
        CompressionOptions options = compression == null ? CompressionOptions.none() : compression;
        // 下行解压用本端配置的同一本字典：服务端会回显客户端在上行“亮明”的字典（见 ServerProxyRuntime 协商）。
        byte[] clientDict = options.hasDictionary() ? options.dictionary() : null;
        try (Socket client = localClient; Socket upstream = new Socket()) {
            LOGGER.info(
                "zstdnet: opening ZSTD upstream {} -> {}:{} level {}",
                client.getRemoteSocketAddress(),
                remoteHost,
                remotePort,
                level
            );
            upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);
            LOGGER.info(
                "zstdnet: ZSTD upstream connected local {} -> remote {}",
                upstream.getLocalSocketAddress(),
                upstream.getRemoteSocketAddress()
            );

            // 跨会话持久化（v2）：打开本服务器的磁盘缓存，取本会话 warm 快照。其键集就是要发给服务端的 manifest，
            // 也是 WARM_REF 重放的来源。持久化关闭时为 null/空——仍 advertise + 发空 manifest（服务端按 advertise 版本确定读取）。
            final ChunkCacheStore cacheStore = (clientCacheEnabled && clientCachePersist)
                ? ChunkCacheStore.open(remoteHost, remotePort, clientCachePersistBytes)
                : null;
            final Map<Hash128, byte[]> warmSnapshot = cacheStore != null ? cacheStore.snapshotWarm() : Map.of();

            Future<?> upstreamWriter = WORKERS.submit(() -> {
                try (ZstdOutputStream zstdOut = ZstdStreams.newCompressor(
                    new CountingOutputStream(upstream.getOutputStream(), stats::addClientToServerZstd),
                    level,
                    options,
                    options.hasDictionary()
                )) {
                    OutputStream countedRawOut = new CountingOutputStream(zstdOut, stats::addClientToServerRaw);
                    PacketIo.writePacket(countedRawOut, firstPacket);
                    // advertise 了 ccache（>=v2）就紧跟一个 ZNCM manifest 帧（即便空）——服务端据 advertise 版本恰好读一帧。
                    if (clientCacheEnabled) {
                        PacketIo.writePacket(countedRawOut, ChunkManifest.encode(warmSnapshot.keySet()));
                    }
                    countedRawOut.flush();
                    StreamTransfer.copyAndFlush(client.getInputStream(), countedRawOut);
                } catch (Exception ignored) {
                } finally {
                    try {
                        upstream.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            Future<?> downstreamWriter = WORKERS.submit(() -> {
                try (ZstdInputStream zstdIn = ZstdStreams.newDecompressor(
                    new CountingInputStream(upstream.getInputStream(), stats::addServerToClientZstd),
                    options,
                    clientDict
                )) {
                    // 启用变换 / CRC 时套上逆向包装；各层 MAGIC 自检：服务端实际未用该层则原样透传，不破坏字节。
                    // CRC 在最外层（与服务端编码端 CRC-在-zstd-之前 对称）；CRC 与实体变换服务端互斥，故至多一层生效、另一层透传。
                    InputStream downstream = zstdIn;
                    if (clientTransform.enabled()) {
                        downstream = new UntransformingInputStream(downstream);
                    }
                    if (clientCacheEnabled) {
                        downstream = new CacheUntransformingInputStream(
                            downstream, ChunkCacheFormat.DEFAULT_CACHE_BYTES, warmSnapshot, cacheStore, stats.cacheStats);
                    }
                    StreamTransfer.copyAndFlush(downstream, new CountingOutputStream(client.getOutputStream(), stats::addServerToClientRaw));
                } catch (Exception ignored) {
                } finally {
                    try {
                        client.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            upstreamWriter.get();
            downstreamWriter.get();
        }
    }

    private static byte[] readPacketWithRetry(InputStream in, int maxWaitMillis) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(200L, maxWaitMillis);
        byte[] prefix = new byte[5];
        int prefixLength = 0;
        Integer payloadLength = null;
        int payloadStart = -1;
        byte[] payload = null;
        int payloadRead = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                int next = in.read();
                if (next < 0) {
                    if (prefixLength == 0 && payloadRead == 0) {
                        return new byte[0];
                    }
                    throw new EOFException("unexpected eof during packet read");
                }

                if (payloadLength == null) {
                    if (prefixLength >= prefix.length) {
                        throw new IOException("packet length varint too large");
                    }
                    prefix[prefixLength++] = (byte) next;
                    VarIntRead packetLength = VarIntCodec.read(prefix, 0, prefixLength);
                    if (packetLength != null) {
                        payloadLength = packetLength.value();
                        payloadStart = packetLength.next();
                        if (payloadLength <= 0) {
                            return new byte[0];
                        }
                        payload = new byte[payloadLength];
                        int extra = prefixLength - payloadStart;
                        if (extra > 0) {
                            System.arraycopy(prefix, payloadStart, payload, 0, extra);
                            payloadRead = extra;
                        }
                    }
                } else {
                    payload[payloadRead++] = (byte) next;
                }

                if (payloadLength != null && payloadRead >= payloadLength) {
                    return payload;
                }
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException e) {
                String message = e.getMessage();
                if (message != null && message.toLowerCase().contains("timed out")) {
                    continue;
                }
                throw e;
            }
        }

        return new byte[0];
    }

    private static Integer extractHandshakeNextState(byte[] handshakePayload) {
        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0);
        if (packetId == null || packetId.value() != 0) {
            return null;
        }

        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next());
        if (protocol == null) {
            return null;
        }

        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next());
        if (hostLength == null || hostLength.value() < 0) {
            return null;
        }

        int afterHost = hostLength.next() + hostLength.value();
        int afterPort = afterHost + 2;
        if (afterPort > handshakePayload.length) {
            return null;
        }

        VarIntRead nextState = VarIntCodec.read(handshakePayload, afterPort);
        return nextState == null ? null : nextState.value();
    }

    private static byte[] rewriteHandshakeDestination(byte[] handshakePayload, String host, int port) {
        if (handshakePayload == null || handshakePayload.length == 0 || host == null || host.isBlank()) {
            return handshakePayload;
        }

        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0);
        if (packetId == null || packetId.value() != 0) {
            return handshakePayload;
        }

        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next());
        if (protocol == null) {
            return handshakePayload;
        }

        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next());
        if (hostLength == null || hostLength.value() < 0) {
            return handshakePayload;
        }

        int hostStart = hostLength.next();
        int hostEnd = hostStart + hostLength.value();
        int portStart = hostEnd;
        int portEnd = portStart + 2;
        if (portEnd > handshakePayload.length) {
            return handshakePayload;
        }

        String originalHost = new String(handshakePayload, hostStart, hostLength.value(), StandardCharsets.UTF_8);
        String hostSuffix = Platforms.get().adjustHandshakeHostSuffix(extractHandshakeHostSuffix(originalHost));
        // advertise 本端支持的最高变换版本（服务端据此决定是否对下行变换）。默认关闭时不追加，握手与历史一致。
        TransformOptions ct = clientTransform;
        if (ct.enabled()) {
            hostSuffix = hostSuffix + TransformHandshake.advertiseSuffix(ct.maxVersion());
        }
        // advertise 区块引用缓存能力（服务端据此决定是否对下行启用 CRC）。默认开启 → 自动协商；与 xform 标记独立共存。
        if (clientCacheEnabled) {
            hostSuffix = hostSuffix + ChunkCacheHandshake.advertiseSuffix(ChunkCacheFormat.MAX_SUPPORTED_VERSION);
        }
        byte[] hostBytes = (host + hostSuffix).getBytes(StandardCharsets.UTF_8);
        return ByteArrayOps.concat(
            ByteArrayOps.slice(handshakePayload, 0, protocol.next()),
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            new byte[]{(byte) (port >>> 8), (byte) port},
            ByteArrayOps.slice(handshakePayload, portEnd, handshakePayload.length)
        );
    }

    private static String extractHandshakeHostSuffix(String originalHost) {
        if (originalHost == null || originalHost.isEmpty()) {
            return "";
        }
        int markerIndex = originalHost.indexOf('\0');
        if (markerIndex < 0) {
            return "";
        }
        return originalHost.substring(markerIndex);
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class UdpForwarder {
        private static final long SESSION_IDLE_TIMEOUT_MS = 60_000L;

        private final String remoteHost;
        private final int remotePort;
        private final int localPort;
        private final AtomicBoolean running = new AtomicBoolean();
        private final Map<SocketAddress, UdpSession> sessions = new ConcurrentHashMap<>();
        private DatagramSocket localSocket;
        private InetSocketAddress target;
        private Thread forwardThread;

        private UdpForwarder(String remoteHost, int remotePort, int localPort) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.localPort = localPort;
        }

        private void start() throws IOException {
            localSocket = new DatagramSocket(null);
            localSocket.setReuseAddress(true);
            localSocket.bind(new InetSocketAddress("127.0.0.1", localPort));

            target = new InetSocketAddress(remoteHost, remotePort);
            running.set(true);

            forwardThread = new Thread(this::forwardLoop, "zstdnet-udp-fwd-" + UDP_SEQ.getAndIncrement() + "-" + remoteHost + ":" + remotePort);
            forwardThread.setDaemon(true);
            forwardThread.start();
        }

        private void stop() {
            running.set(false);
            if (localSocket != null) {
                localSocket.close();
            }
            sessions.values().forEach(UdpSession::stop);
            sessions.clear();
            joinQuietly(forwardThread);
        }

        private void forwardLoop() {
            byte[] buf = new byte[UDP_BUF_SIZE];

            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    localSocket.receive(packet);

                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                    sessionFor(packet.getSocketAddress()).send(data);
                    cleanupExpiredSessions();
                } catch (SocketException e) {
                    if (running.get()) {
                        LOGGER.debug("zstdnet: UDP passthrough forward socket closed: {}", e.toString());
                    }
                    return;
                } catch (IOException e) {
                    if (running.get()) {
                        LOGGER.debug("zstdnet: UDP passthrough forward error: {}", e.toString());
                    }
                }
            }
        }

        private UdpSession sessionFor(SocketAddress clientAddress) throws IOException {
            UdpSession existing = sessions.get(clientAddress);
            if (existing != null) {
                return existing;
            }

            UdpSession created = new UdpSession(clientAddress);
            UdpSession raced = sessions.putIfAbsent(clientAddress, created);
            if (raced != null) {
                created.stop();
                return raced;
            }

            created.start();
            return created;
        }

        private void cleanupExpiredSessions() {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> {
                UdpSession session = entry.getValue();
                if (now - session.lastActivityMs() <= SESSION_IDLE_TIMEOUT_MS) {
                    return false;
                }
                session.stop();
                return true;
            });
        }

        private final class UdpSession {
            private final SocketAddress clientAddress;
            private final DatagramSocket upstreamSocket;
            private volatile long lastActivityMs = System.currentTimeMillis();
            private Thread returnThread;

            private UdpSession(SocketAddress clientAddress) throws SocketException {
                this.clientAddress = clientAddress;
                this.upstreamSocket = new DatagramSocket();
            }

            private void start() {
                returnThread = new Thread(this::returnLoop, "zstdnet-udp-ret-" + UDP_SEQ.getAndIncrement() + "-" + remoteHost + ":" + remotePort);
                returnThread.setDaemon(true);
                returnThread.start();
            }

            private void send(byte[] data) throws IOException {
                lastActivityMs = System.currentTimeMillis();
                upstreamSocket.send(new DatagramPacket(data, data.length, target));
            }

            private long lastActivityMs() {
                return lastActivityMs;
            }

            private void stop() {
                upstreamSocket.close();
                joinQuietly(returnThread);
            }

            private void returnLoop() {
                byte[] buf = new byte[UDP_BUF_SIZE];

                while (running.get() && !upstreamSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        upstreamSocket.receive(packet);
                        lastActivityMs = System.currentTimeMillis();

                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                        localSocket.send(new DatagramPacket(data, data.length, clientAddress));
                    } catch (SocketException e) {
                        if (running.get()) {
                            LOGGER.debug("zstdnet: UDP passthrough return socket closed: {}", e.toString());
                        }
                        return;
                    } catch (IOException e) {
                        if (running.get()) {
                            LOGGER.debug("zstdnet: UDP passthrough return error: {}", e.toString());
                        }
                    }
                }
            }
        }

        private void joinQuietly(Thread thread) {
            if (thread == null) {
                return;
            }
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 客户端「单端口语音隧道」转发器（tunnel 模式）。
     *
     * <p>在 {@code 127.0.0.1:<语音端口>} 监听语音 mod 发来的 UDP（语音 mod 把服务器地址解析成了本地代理的
     * 127.0.0.1，端口取自它自己的握手），加上 {@link VoiceTunnelFrame} 包头后发往 zstdnet 的公网入口端口
     * （与游戏 UDP 同一个端口）；回程剥头后投回语音 mod。服务端按 channelId 拆分到后端对应语音端口。</p>
     */
    private static final class VoiceTunnelForwarder {
        private static final long SESSION_IDLE_TIMEOUT_MS = 60_000L;

        private final String remoteHost;
        private final int entryPort;
        private final int localPort;
        private final int channelId;
        private final AtomicBoolean running = new AtomicBoolean();
        private final Map<SocketAddress, Session> sessions = new ConcurrentHashMap<>();
        private DatagramSocket localSocket;
        private InetSocketAddress entry;
        private Thread forwardThread;

        private VoiceTunnelForwarder(String remoteHost, int entryPort, int localPort, int channelId) {
            this.remoteHost = remoteHost;
            this.entryPort = entryPort;
            this.localPort = localPort;
            this.channelId = channelId;
        }

        private void start() throws IOException {
            localSocket = new DatagramSocket(null);
            localSocket.setReuseAddress(true);
            localSocket.bind(new InetSocketAddress("127.0.0.1", localPort));

            entry = new InetSocketAddress(remoteHost, entryPort);
            running.set(true);

            forwardThread = new Thread(
                this::forwardLoop,
                "zstdnet-voice-fwd-" + UDP_SEQ.getAndIncrement() + "-ch" + channelId + "-" + localPort
            );
            forwardThread.setDaemon(true);
            forwardThread.start();
        }

        private void stop() {
            running.set(false);
            if (localSocket != null) {
                localSocket.close();
            }
            sessions.values().forEach(Session::stop);
            sessions.clear();
            joinQuietly(forwardThread);
        }

        private void forwardLoop() {
            byte[] buf = new byte[UDP_BUF_SIZE];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    localSocket.receive(packet);

                    byte[] framed = VoiceTunnelFrame.wrap(channelId, packet.getData(), packet.getOffset(), packet.getLength());
                    sessionFor(packet.getSocketAddress()).send(framed);
                    cleanupExpiredSessions();
                } catch (SocketException e) {
                    if (running.get()) {
                        LOGGER.debug("zstdnet: voice tunnel forward socket closed: {}", e.toString());
                    }
                    return;
                } catch (IOException e) {
                    if (running.get()) {
                        LOGGER.debug("zstdnet: voice tunnel forward error: {}", e.toString());
                    }
                }
            }
        }

        private Session sessionFor(SocketAddress clientAddress) throws IOException {
            Session existing = sessions.get(clientAddress);
            if (existing != null) {
                return existing;
            }
            Session created = new Session(clientAddress);
            Session raced = sessions.putIfAbsent(clientAddress, created);
            if (raced != null) {
                created.stop();
                return raced;
            }
            created.start();
            return created;
        }

        private void cleanupExpiredSessions() {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> {
                Session session = entry.getValue();
                if (now - session.lastActivityMs <= SESSION_IDLE_TIMEOUT_MS) {
                    return false;
                }
                session.stop();
                return true;
            });
        }

        private void joinQuietly(Thread thread) {
            if (thread == null) {
                return;
            }
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private final class Session {
            private final SocketAddress clientAddress;
            private final DatagramSocket upstreamSocket;
            private volatile long lastActivityMs = System.currentTimeMillis();
            private Thread returnThread;

            private Session(SocketAddress clientAddress) throws SocketException {
                this.clientAddress = clientAddress;
                this.upstreamSocket = new DatagramSocket();
            }

            private void start() {
                returnThread = new Thread(
                    this::returnLoop,
                    "zstdnet-voice-ret-" + UDP_SEQ.getAndIncrement() + "-ch" + channelId + "-" + localPort
                );
                returnThread.setDaemon(true);
                returnThread.start();
            }

            private void send(byte[] framed) throws IOException {
                lastActivityMs = System.currentTimeMillis();
                upstreamSocket.send(new DatagramPacket(framed, framed.length, entry));
            }

            private void stop() {
                upstreamSocket.close();
                joinQuietly(returnThread);
            }

            private void returnLoop() {
                byte[] buf = new byte[UDP_BUF_SIZE];
                while (running.get() && !upstreamSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        upstreamSocket.receive(packet);
                        lastActivityMs = System.currentTimeMillis();

                        // 只接受属于本通道的 ZV1 回程帧，剥头后投回语音 mod。
                        if (!VoiceTunnelFrame.isFrame(packet.getData(), packet.getOffset(), packet.getLength())
                            || VoiceTunnelFrame.channelId(packet.getData(), packet.getOffset()) != channelId) {
                            continue;
                        }
                        int payloadOff = packet.getOffset() + VoiceTunnelFrame.HEADER_LEN;
                        int payloadLen = packet.getLength() - VoiceTunnelFrame.HEADER_LEN;
                        byte[] data = new byte[payloadLen];
                        System.arraycopy(packet.getData(), payloadOff, data, 0, payloadLen);
                        localSocket.send(new DatagramPacket(data, data.length, clientAddress));
                    } catch (SocketException e) {
                        if (running.get()) {
                            LOGGER.debug("zstdnet: voice tunnel return socket closed: {}", e.toString());
                        }
                        return;
                    } catch (IOException e) {
                        if (running.get()) {
                            LOGGER.debug("zstdnet: voice tunnel return error: {}", e.toString());
                        }
                    }
                }
            }
        }
    }

    public record HostPort(String host, int port) {
        public static HostPort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("empty addr");
            }

            String value = raw.trim();
            if (value.startsWith("[") && value.contains("]")) {
                int end = value.indexOf(']');
                String host = value.substring(1, end);
                if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                    int port = Integer.parseInt(value.substring(end + 2).trim());
                    return new HostPort(host, port);
                }
                return new HostPort(host, 25565);
            }

            int lastColon = value.lastIndexOf(':');
            int firstColon = value.indexOf(':');
            if (lastColon > 0 && firstColon == lastColon) {
                String host = value.substring(0, lastColon).trim();
                int port = Integer.parseInt(value.substring(lastColon + 1).trim());
                return new HostPort(host, port);
            }

            return new HostPort(value, 25565);
        }
    }

    public static final class ProxyHandle implements AutoCloseable {
        private final ServerSocket listener;
        private final AtomicBoolean running;
        private final Thread acceptThread;
        private final UdpForwarder udpForwarder;
        private final Mode mode;
        private final String remoteHost;
        private final int remotePort;
        private final ProxyStats stats;
        private final Object voiceLock = new Object();
        private final List<Runnable> voiceStops = new ArrayList<>();

        private ProxyHandle(
            ServerSocket listener,
            AtomicBoolean running,
            Thread acceptThread,
            UdpForwarder udpForwarder,
            Mode mode,
            String remoteHost,
            int remotePort,
            ProxyStats stats
        ) {
            this.listener = listener;
            this.running = running;
            this.acceptThread = acceptThread;
            this.udpForwarder = udpForwarder;
            this.mode = mode;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.stats = stats;
        }

        public int localPort() {
            return listener.getLocalPort();
        }

        public Mode mode() {
            return mode;
        }

        public String remoteHost() {
            return remoteHost;
        }

        public int remotePort() {
            return remotePort;
        }

        public StatsSnapshot statsSnapshot() {
            return stats.snapshot(mode, remoteHost, remotePort);
        }

        /**
         * 按服务端下发的语音端口计划，在本机为每个语音端口开监听（零配置兼容各类语音 mod）。
         * 可重复调用（先全部撤销再重建），由服务端在玩家进服后下发触发（见各变体 VoicePortSync）。
         *
         * @param transport {@code tunnel}（语音也走入口端口）或 {@code bridge}（直连真实服务器同端口）
         * @param ports     服务端探测到的语音端口（有序，下标即隧道 channelId）
         */
        public void armVoicePorts(String transport, List<Integer> ports) {
            synchronized (voiceLock) {
                disarmVoicePortsLocked();
                if (ports == null || ports.isEmpty()) {
                    return;
                }
                boolean bridge = "bridge".equalsIgnoreCase(transport);
                for (int i = 0; i < ports.size(); i++) {
                    int voicePort = ports.get(i);
                    if (voicePort < 1 || voicePort > 65535) {
                        continue;
                    }
                    try {
                        voiceStops.add(bridge ? startVoiceBridge(voicePort) : startVoiceTunnel(i, voicePort));
                    } catch (IOException e) {
                        LOGGER.warn("zstdnet: failed to arm voice port {} ({}): {}", voicePort, bridge ? "bridge" : "tunnel", e.toString());
                    }
                }
            }
        }

        /** 撤销所有已开的语音监听（断开连接 / 重新下发时调用）。 */
        public void disarmVoicePorts() {
            synchronized (voiceLock) {
                disarmVoicePortsLocked();
            }
        }

        private void disarmVoicePortsLocked() {
            for (Runnable stop : voiceStops) {
                try {
                    stop.run();
                } catch (Exception ignored) {
                }
            }
            voiceStops.clear();
        }

        private Runnable startVoiceBridge(int voicePort) throws IOException {
            UdpForwarder forwarder = new UdpForwarder(remoteHost, voicePort, voicePort);
            forwarder.start();
            LOGGER.info("zstdnet: voice bridge armed 127.0.0.1:{} -> {}:{}", voicePort, remoteHost, voicePort);
            return forwarder::stop;
        }

        private Runnable startVoiceTunnel(int channelId, int voicePort) throws IOException {
            VoiceTunnelForwarder forwarder = new VoiceTunnelForwarder(remoteHost, remotePort, voicePort, channelId);
            forwarder.start();
            LOGGER.info("zstdnet: voice tunnel armed 127.0.0.1:{} -> {}:{} (channel {})", voicePort, remoteHost, remotePort, channelId);
            return forwarder::stop;
        }

        public void closeListener() {
            int localPort = localPort();
            boolean wasRunning = running.getAndSet(false);
            LOGGER.info(
                "zstdnet: closing TCP listener 127.0.0.1:{} -> {}:{} (wasRunning={})",
                localPort,
                remoteHost,
                remotePort,
                wasRunning
            );
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            try {
                acceptThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            running.set(false);
            disarmVoicePorts();
            if (udpForwarder != null) {
                udpForwarder.stop();
            }
            closeListener();
        }
    }

    public record StatsSnapshot(
        Mode mode,
        String remoteHost,
        int remotePort,
        long rawUpBytes,
        long rawDownBytes,
        long wireUpBytes,
        long wireDownBytes,
        long rawUpRate,
        long rawDownRate,
        long wireUpRate,
        long wireDownRate,
        double ratioPercent,
        long cacheRefHits,
        long cacheWarmHits,
        long cachePatchHits,
        long cacheSavedBytes
    ) {
        /** 区块引用缓存是否产生过收益（任一 REF/WARM/PATCH 命中）。 */
        public boolean hasCacheActivity() {
            return cacheRefHits > 0 || cacheWarmHits > 0 || cachePatchHits > 0;
        }
    }

    private static final class ProxyStats {
        private static final long RATE_SAMPLE_INTERVAL_MS = 500L;

        private final AtomicLong rawUpBytes = new AtomicLong();
        private final AtomicLong rawDownBytes = new AtomicLong();
        private final AtomicLong wireUpBytes = new AtomicLong();
        private final AtomicLong wireDownBytes = new AtomicLong();
        /** 客户端区块引用缓存命中计数（由各连接的 CacheUntransformingInputStream 旁路更新）。 */
        private final CacheStats cacheStats = new CacheStats();

        private volatile long sampleAtMs = System.currentTimeMillis();
        private volatile long sampledRawUpBytes;
        private volatile long sampledRawDownBytes;
        private volatile long sampledWireUpBytes;
        private volatile long sampledWireDownBytes;
        private volatile long rawUpRate;
        private volatile long rawDownRate;
        private volatile long wireUpRate;
        private volatile long wireDownRate;

        private void addClientToServerRaw(long bytes) {
            if (bytes > 0) {
                rawUpBytes.addAndGet(bytes);
            }
        }

        private void addServerToClientRaw(long bytes) {
            if (bytes > 0) {
                rawDownBytes.addAndGet(bytes);
            }
        }

        private void addClientToServerZstd(long bytes) {
            if (bytes > 0) {
                wireUpBytes.addAndGet(bytes);
            }
        }

        private void addServerToClientZstd(long bytes) {
            if (bytes > 0) {
                wireDownBytes.addAndGet(bytes);
            }
        }

        private void addClientToServerRawPassthrough(long bytes) {
            if (bytes > 0) {
                rawUpBytes.addAndGet(bytes);
                wireUpBytes.addAndGet(bytes);
            }
        }

        private void addServerToClientRawPassthrough(long bytes) {
            if (bytes > 0) {
                rawDownBytes.addAndGet(bytes);
                wireDownBytes.addAndGet(bytes);
            }
        }

        private synchronized StatsSnapshot snapshot(Mode mode, String remoteHost, int remotePort) {
            long now = System.currentTimeMillis();
            long currentRawUp = rawUpBytes.get();
            long currentRawDown = rawDownBytes.get();
            long currentWireUp = wireUpBytes.get();
            long currentWireDown = wireDownBytes.get();

            long elapsedMs = now - sampleAtMs;
            if (elapsedMs >= RATE_SAMPLE_INTERVAL_MS) {
                rawUpRate = scaleRate(currentRawUp - sampledRawUpBytes, elapsedMs);
                rawDownRate = scaleRate(currentRawDown - sampledRawDownBytes, elapsedMs);
                wireUpRate = scaleRate(currentWireUp - sampledWireUpBytes, elapsedMs);
                wireDownRate = scaleRate(currentWireDown - sampledWireDownBytes, elapsedMs);

                sampledRawUpBytes = currentRawUp;
                sampledRawDownBytes = currentRawDown;
                sampledWireUpBytes = currentWireUp;
                sampledWireDownBytes = currentWireDown;
                sampleAtMs = now;
            }

            long totalRaw = currentRawUp + currentRawDown;
            long totalWire = currentWireUp + currentWireDown;
            double ratio = totalRaw <= 0 ? 0.0D : (double) totalWire * 100.0D / (double) totalRaw;
            return new StatsSnapshot(
                mode,
                remoteHost,
                remotePort,
                currentRawUp,
                currentRawDown,
                currentWireUp,
                currentWireDown,
                rawUpRate,
                rawDownRate,
                wireUpRate,
                wireDownRate,
                ratio,
                cacheStats.refHits(),
                cacheStats.warmHits(),
                cacheStats.patchHits(),
                cacheStats.savedBytes()
            );
        }

        private long scaleRate(long deltaBytes, long elapsedMs) {
            if (deltaBytes <= 0 || elapsedMs <= 0) {
                return 0L;
            }
            return Math.max(0L, Math.round(deltaBytes * (1000.0D / elapsedMs)));
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final Counter counter;

        private CountingInputStream(InputStream delegate, Counter counter) {
            this.delegate = Objects.requireNonNull(delegate);
            this.counter = Objects.requireNonNull(counter);
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                counter.add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                counter.add(read);
            }
            return read;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final Counter counter;

        private CountingOutputStream(OutputStream delegate, Counter counter) {
            this.delegate = Objects.requireNonNull(delegate);
            this.counter = Objects.requireNonNull(counter);
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            counter.add(1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            if (len > 0) {
                counter.add(len);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    @FunctionalInterface
    private interface Counter {
        void add(long bytes);
    }
}
