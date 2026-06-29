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

package cn.tohsaka.factory.zstdnet.server;

import cn.tohsaka.factory.zstdnet.Branding;
import cn.tohsaka.factory.zstdnet.core.cache.CacheMode;
import cn.tohsaka.factory.zstdnet.core.cache.CacheTransformingOutputStream;
import cn.tohsaka.factory.zstdnet.core.cache.CacheablePacketTable;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheFormat;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheHandshake;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheMeasurement;
import cn.tohsaka.factory.zstdnet.core.cache.ChunkManifest;
import cn.tohsaka.factory.zstdnet.core.cache.Hash128;
import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.compress.DictionaryFiles;
import cn.tohsaka.factory.zstdnet.core.compress.DictionarySampler;
import cn.tohsaka.factory.zstdnet.core.compress.DictionaryTrainer;
import cn.tohsaka.factory.zstdnet.core.compress.ZstdCodecs;
import cn.tohsaka.factory.zstdnet.core.compress.ZstdStreams;
import cn.tohsaka.factory.zstdnet.core.io.CountingInputStream;
import cn.tohsaka.factory.zstdnet.core.io.CountingOutputStream;
import cn.tohsaka.factory.zstdnet.core.limit.TokenBucketLimiter;
import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.core.protocol.LoginDisconnect;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import cn.tohsaka.factory.zstdnet.core.protocol.VoiceTunnelFrame;
import cn.tohsaka.factory.zstdnet.core.stats.TrafficStats;
import cn.tohsaka.factory.zstdnet.core.transform.EntityPacketTable;
import cn.tohsaka.factory.zstdnet.core.transform.TransformFormat;
import cn.tohsaka.factory.zstdnet.core.transform.TransformHandshake;
import cn.tohsaka.factory.zstdnet.core.transform.TransformOptions;
import cn.tohsaka.factory.zstdnet.core.transform.TransformingOutputStream;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内置服务端代理运行时模块。
 * <p>
 * 负责：
 * 1) 读取/生成配置并监听入口端口；
 * 2) 双向转发（客户端->后端解压，后端->客户端压缩）；
 * 3) 限流、防刷与连接统计；
 * 4) 管理线程池与生命周期启停。
 */
final class ServerProxyRuntime {
    static final String ZSTD_ADDRESS_HINT = "当前服务器启用了 ZSTD 连接，请联系服务器管理员获取正确的连接地址。";

    // 后端 MC 服务器不可达 / 崩溃 / 重启时给登录态 ZSTD 客户端的真实原因。代理在线但后端没了——客户端那边只能笼统判为
    // 「无响应」，由我方（代理）补发明确原因。注意：客户端下行走 zstd 解压，故必须压缩后再写（见 sendCompressedLoginDisconnect）。
    private static final String BACKEND_UNAVAILABLE_MSG =
        "ZstdNet：后端 Minecraft 服务器暂时不可用。\n\n"
        + "ZstdNet 代理在线，但后端游戏服务器没有响应——可能正在重启，或刚刚崩溃。\n"
        + "请稍后重试。\n\n"
        + "The backend Minecraft server is temporarily unavailable "
        + "(it may be restarting or has just crashed). Please try again later.";

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerProxyRuntime.class);
    private static final byte[] PROXY_V2_SIGNATURE = new byte[]{
        0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a
    };
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int DEFAULT_LISTEN_PORT = 35565;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final int DEFAULT_VOICE_CHAT_PORT = 24454;
    private static final int DEFAULT_VOICE_CHAT_LISTEN_PORT = 24455;
    private static final int DEFAULT_ZSTD_LEVEL = 9;
    private static final int DEFAULT_MAX_CONN_PER_IP = 9999;
    private static final int DEFAULT_MAX_REQ_PER_WINDOW = 50;
    private static final int DEFAULT_BURST_BYTES = 256 * 1024;
    private static final Duration DEFAULT_BAN_DURATION = Duration.ofMinutes(1);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ZERO;
    private static final String DEFAULT_TRUSTED_PROXY_IPS = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
    private static final int CLIENT_PEEK_BUFFER = 4096;
    /**
     * dictionary_auto：累计采到这么多个「样本块」后自动训练。
     * <p>采样器把每条连接切成多个 16KB 块（见 {@link DictionarySampler}），所以这是块数不是人数——
     * 大整合包一名玩家登录就产好几个块，通常两三名玩家正常进服一次即可达标，无需任何人反复进出。
     */
    private static final int AUTO_TRAIN_MIN_SAMPLES = 16;
    /** dictionary_auto：自动训练目标字典大小（ZDICT 会按实际语料自动收缩，故对小服也安全）。 */
    private static final int AUTO_TRAIN_DICT_BYTES = 64 * 1024;
    /** dictionary_auto：后台轮询间隔（秒）。 */
    private static final long AUTO_TRAIN_POLL_SECONDS = 30L;
    private static final int MAX_HANDSHAKE_PACKET_SIZE = 2048;
    private static final int MAX_PROXY_V2_PAYLOAD_SIZE = 216;

    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;
    private ExecutorService workers;
    private ScheduledExecutorService statsTicker;
    private FloodGuard guard;
    private TrafficStats stats;
    private volatile ProxyConfig cfg;
    private volatile DictionarySampler dictionarySampler;
    /** chunk_cache=measure 模式的只读埋点（懒初始化，全运行时一份，跨连接累计跨会话重复统计）。 */
    private volatile ChunkCacheMeasurement chunkCacheMeasurement;
    /** 已对“启用 chunk_cache 但该协议无可缓存包表”告警过的协议版本集（每版本仅刷一次，避免刷屏）。 */
    private final Set<Integer> chunkCacheUnsupportedWarned = ConcurrentHashMap.newKeySet();
    /** dictionary_auto 模式的后台「采样够了就训练」轮询器。 */
    private ScheduledExecutorService autoDictWatcher;
    /**
     * 自动训练完成、字典已热插进运行时后置为该字典 id（非 0）。per-variant 的 ServerProxyBootstrap 在 tick 里
     * {@link #consumeDictionaryRolloutId()} 取走它，向所有在线玩家广播下发，使在线玩家也立即拿到字典并重连生效。
     */
    private volatile long dictionaryRolloutId;
    private TokenBucketLimiter globalLimiter;
    private RuntimeMode runtimeMode;
    private volatile HudSnapshot latestHudSnapshot;
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private List<UdpForwarder> udpForwarders = Collections.emptyList();
    private volatile VoicePortPlan voicePortPlan = VoicePortPlan.empty();

    /**
     * 启动运行时。
     */
    void start(int mcServerPort) {
        start(mcServerPort, RuntimeMode.DEDICATED);
    }

    void startLan(int lanPort) {
        start(lanPort, RuntimeMode.LAN);
    }

    private void start(int mcServerPort, RuntimeMode mode) {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }

            Path configPath = ServerProxyConfigFile.path();
            ProxyConfig loaded = loadOrCreateConfig(configPath, mcServerPort, mode);
            if (loaded == null) {
                return;
            }
            if (!loaded.enabled) {
                LOGGER.warn("[zstdnet-server] config exists but enabled=false, skip start. File: {}", configPath);
                LOGGER.warn("[zstdnet-server] set enabled=true after checking listen/target.");
                return;
            }

            if (mode == RuntimeMode.DEDICATED && loaded.autoTakeover) {
                AutoPortPlan plan = DedicatedAutoPortState.activePlan();
                if (plan == null) {
                    LOGGER.error("[zstdnet-server] auto_takeover=true but no dedicated auto-port plan was prepared. Restart the server and check coremod logs.");
                    return;
                }
                loaded = loaded.withEndpoints(
                    new HostPort(plan.listenHost(), plan.listenPort()),
                    new HostPort(plan.targetHost(), plan.targetPort())
                );
            }

            if (mode == RuntimeMode.LAN) {
                loaded = loaded.withTarget(new HostPort("127.0.0.1", mcServerPort));
                loaded = loaded.withLanVoiceDefaults(mcServerPort);
            }

            BindResult bindResult = bindListener(loaded, mode);
            if (bindResult == null) {
                return;
            }
            listener = bindResult.listener();
            loaded = bindResult.config();

            this.cfg = loaded;
            this.stats = new TrafficStats();
            this.guard = new FloodGuard(loaded);
            this.workers = Executors.newCachedThreadPool(new NamedFactory("zstdsrv-worker"));
            this.statsTicker = Executors.newSingleThreadScheduledExecutor(new NamedFactory("zstdsrv-stats"));
            this.globalLimiter = TokenBucketLimiter.create(loaded.maxRateGlobalBps, loaded.burstBytes);
            this.runtimeMode = mode;
            this.latestHudSnapshot = new HudSnapshot(mode, loaded.listen.host, loaded.listen.port, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0D, 0);
            this.running = true;

            startStatsPrinter();
            acceptThread = new Thread(this::acceptLoop, "zstdsrv-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            startUdpForwarders(loaded);

            LOGGER.info("[zstdnet-server] started: mode={} listen={} target={}", mode.name().toLowerCase(Locale.ROOT), loaded.listen, loaded.target);
            LOGGER.info("[zstdnet-server] ZstdNet powered by minekuai.com");
            // 启动后整条推广语重复打印若干次（麦块联机 minekuai.com）。
            for (int adRepeat = 0; adRepeat < Branding.SERVER_LOG_REPEAT; adRepeat++) {
                LOGGER.info("[zstdnet-server] {}", Branding.AD);
            }
            if (mode == RuntimeMode.LAN) {
                LOGGER.info("[zstdnet-server] LAN host detected. Point your tunnel to {} instead of the raw LAN port {}.", loaded.listen, mcServerPort);
            }
            LOGGER.info("[zstdnet-server] guard: max_conn={} max_req={} window={} ban={}",
                loaded.maxConnPerIp, loaded.maxReqPerWindow, loaded.window, loaded.banDuration);
            LOGGER.info("[zstdnet-server] tuning: flush={} idle_timeout={} rate_per_conn={}B/s rate_global={}B/s burst={}B",
                loaded.flushInterval, loaded.idleTimeout, loaded.maxRatePerConnBps, loaded.maxRateGlobalBps, loaded.burstBytes);
            if (loaded.autoTakeover && mode == RuntimeMode.DEDICATED) {
                LOGGER.info("[zstdnet-server] auto takeover active: public_entry={} backend={}", loaded.listen, loaded.target);
            }
            LOGGER.info("[zstdnet-server] raw vanilla login passthrough: disabled");
        }
    }

    private BindResult bindListener(ProxyConfig config, RuntimeMode mode) {
        if (mode == RuntimeMode.LAN && isReservedLanListenPort(config, config.listen.port)) {
            LOGGER.warn("[zstdnet-server] LAN listen port {} is reserved by the current LAN session, trying the next available port.", config.listen);
        } else {
            try {
                return bindListenerOnPort(config, config.listen.port);
            } catch (IOException e) {
                if (mode != RuntimeMode.LAN) {
                    LOGGER.error("[zstdnet-server] bind failed on {}: {}", config.listen, e.toString());
                    return null;
                }
                LOGGER.warn("[zstdnet-server] LAN listen port {} is unavailable, trying the next available port: {}", config.listen, e.toString());
            }
        }

        for (int port = Math.max(1024, config.listen.port + 1); port <= MAX_PORT; port++) {
            BindResult result = tryBindLanListener(config, port);
            if (result != null) {
                return result;
            }
        }
        for (int port = 1024; port < Math.max(1024, config.listen.port); port++) {
            BindResult result = tryBindLanListener(config, port);
            if (result != null) {
                return result;
            }
        }
        LOGGER.error("[zstdnet-server] no available LAN listen port found for host {}", config.listen.host);
        return null;
    }

    private BindResult tryBindLanListener(ProxyConfig config, int port) {
        if (isReservedLanListenPort(config, port)) {
            return null;
        }
        try {
            BindResult result = bindListenerOnPort(config, port);
            LOGGER.info("[zstdnet-server] LAN listen port changed from {} to {} because the preferred port was unavailable.", config.listen.port, port);
            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    private BindResult bindListenerOnPort(ProxyConfig config, int port) throws IOException {
        ProxyConfig resolved = config.withEndpoints(new HostPort(config.listen.host, port), config.target);
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(resolved.listen.toAddress());
            return new BindResult(socket, resolved);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private boolean isReservedLanListenPort(ProxyConfig config, int port) {
        return port == config.target.port;
    }

    private int parseOptionalPort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return -1;
        }
        try {
            return HostPort.parse(raw).port;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * 停止运行时并回收资源。
     */
    void stop() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            running = false;
            stopAutoDictWatcher();
            stopUdpForwarders();
            closeQuietly(listener);
            listener = null;
            if (acceptThread != null) {
                try {
                    acceptThread.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            acceptThread = null;
            shutdownQuietly(statsTicker);
            shutdownQuietly(workers);
            statsTicker = null;
            workers = null;
            guard = null;
            stats = null;
            cfg = null;
            globalLimiter = null;
            runtimeMode = null;
            latestHudSnapshot = null;
            LOGGER.info("[zstdnet-server] stopped");
        }
    }

    /**
     * 接收入口连接并交给工作线程处理。
     */
    boolean isRunning() {
        return running;
    }

    boolean protectsBackendLogin() {
        return running && runtimeMode == RuntimeMode.DEDICATED;
    }

    boolean isLanMode() {
        return running && runtimeMode == RuntimeMode.LAN;
    }

    HudSnapshot hudSnapshot() {
        if (!running || stats == null || cfg == null || runtimeMode == null) {
            return latestHudSnapshot;
        }
        HudSnapshot previous = latestHudSnapshot;
        long rawUpRate = previous == null ? 0L : previous.rawUpRate();
        long rawDownRate = previous == null ? 0L : previous.rawDownRate();
        long zstdUpRate = previous == null ? 0L : previous.zstdUpRate();
        long zstdDownRate = previous == null ? 0L : previous.zstdDownRate();
        return buildHudSnapshot(rawUpRate, rawDownRate, zstdUpRate, zstdDownRate);
    }

    boolean configChangedOnDisk() {
        Path path = ServerProxyConfigFile.path();
        long current = readLastModified(path);
        return current > 0L && current != loadedConfigLastModified;
    }

    /**
     * 取走「自动训练刚完成、字典已热插」的待广播 dict id（取后清零）；无则返回 0。
     * 由 per-variant 的服务器 tick 调用，向所有在线玩家广播字典下发。
     */
    public long consumeDictionaryRolloutId() {
        long id = dictionaryRolloutId;
        if (id != 0L) {
            dictionaryRolloutId = 0L;
        }
        return id;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                workers.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running) {
                    LOGGER.warn("[zstdnet-server] accept error: {}", e.toString());
                }
            } catch (Exception e) {
                if (running) {
                    LOGGER.warn("[zstdnet-server] accept unexpected error: {}", e.toString());
                }
            }
        }
    }

    /**
     * 处理单个客户端连接，建立到后端连接后执行双向转发。
     */
    private void handleClient(Socket client) {
        // 快照当前配置：dictionary_auto 训练完成会原子替换 this.cfg（热插字典），快照确保本连接全程读到一致的一份。
        final ProxyConfig cfg = this.cfg;
        if (cfg == null) {
            closeSocket(client);
            return;
        }
        stats.addConn(1);
        String remoteIp = sourceIp(client.getRemoteSocketAddress());
        String guardIp = remoteIp;

        try (Socket clientSocket = client) {
            PushbackInputStream pushIn = new PushbackInputStream(clientSocket.getInputStream(), CLIENT_PEEK_BUFFER);
            ProxyInfo proxyInfo = parseProxyProtocolV2(pushIn);
            String forwardedSourceIp = resolveForwardedSourceIp(remoteIp, proxyInfo);
            if (forwardedSourceIp == null) {
                return;
            }

            if (!guard.begin(guardIp)) {
                LOGGER.warn("[server] blocked {} by flood guard", guardIp);
                return;
            }

            DetectedClientMode clientMode = detectClientMode(pushIn);
            if (clientMode.mode == ClientMode.RAW_LOGIN) {
                LOGGER.warn("[server] rejected raw login attempt from {} on zstd-only entry", guardIp);
                sendLoginDisconnect(
                    clientSocket,
                    "当前服务器启用了 ZSTD 连接，请联系服务器管理员获取正确的连接方式。\n"
                );
                return;
            }

            try (Socket upstream = new Socket()) {
                try {
                    upstream.connect(cfg.target.toAddress(), 5000);
                } catch (IOException dialErr) {
                    LOGGER.warn("[server] Remote {} Connect Error: {}", clientSocket.getRemoteSocketAddress(), dialErr.getMessage());
                    // 代理在线但后端 MC 拨号失败（崩溃 / 关停 / 重启中）：给登录态 ZSTD 客户端补发真实原因，
                    // 避免客户端只能笼统判为「无响应」。此时字典尚未协商，用无字典帧压缩。状态 ping（RAW_STATUS）不懂登录断开包，不发。
                    if (clientMode.mode == ClientMode.ZSTD) {
                        sendCompressedLoginDisconnect(clientSocket, cfg, false, BACKEND_UNAVAILABLE_MSG);
                    }
                    return;
                }

                upstream.setTcpNoDelay(true);
                clientSocket.setTcpNoDelay(true);
                // Client->server play traffic can legitimately stay idle for a while.
                // Applying SO_TIMEOUT there causes false disconnects in LAN/Forge sessions.
                applyReadTimeout(upstream, cfg.idleTimeout);
                TokenBucketLimiter perConnLimiter = TokenBucketLimiter.create(cfg.maxRatePerConnBps, cfg.burstBytes);

                if (clientMode.mode == ClientMode.RAW_STATUS) {
                    forwardRawPassthrough(clientSocket, pushIn, upstream, clientMode.initialWireData, stats);
                    return;
                }

                // 字典隐式协商：仅当服务端加载了字典时才探测客户端首帧的 dict id。
                // 默认（无字典）下此分支完全不触发，连接建立路径与历史逐字节一致。
                byte[] decompressDict = null;     // client -> backend 解压所用字典
                boolean compressWithDict = false; // backend -> client 是否回显字典压缩
                if (cfg.compression.hasDictionary()) {
                    long clientDictId = ZstdStreams.peekFrameDictId(pushIn);
                    if (clientDictId != 0L) {
                        if (clientDictId == cfg.compression.dictionaryId()) {
                            // 客户端用我方字典亮明能力 -> 上行用它解压，下行回显同一字典压缩。
                            decompressDict = cfg.compression.dictionary();
                            compressWithDict = true;
                        } else {
                            // 客户端用的是另一本字典：我方无法解码其上行，也无法压出它能读的下行，只能关闭。
                            LOGGER.warn(
                                "[server] client {} advertised dictionary id {} not matching server dictionary id {}; closing connection",
                                guardIp,
                                clientDictId,
                                cfg.compression.dictionaryId()
                            );
                            return;
                        }
                    }
                }

                final String backendSourceIp = forwardedSourceIp;
                final byte[] upstreamDict = decompressDict;
                final boolean downstreamDict = compressWithDict;

                // 在派发双向泵之前同步读首包（握手），提取并剥离客户端 transform 能力标记，再决定下行是否变换。
                // 因后端只有在收到我方转发的握手后才会回数据，所以此处读握手不会与 s2c 抢跑。
                InputStream upstreamDecompressor = null;
                int clientTransformVersion = 0;
                int clientProtocolVersion = 0;
                int clientChunkCacheVersion = 0;
                Set<Hash128> clientWarmSet = Collections.emptySet();
                try {
                    upstreamDecompressor = ZstdStreams.newDecompressor(
                        new CountingInputStream(pushIn, stats::addZstdUp), cfg.compression, upstreamDict);
                    byte[] firstPacket = PacketIo.readPacket(upstreamDecompressor);
                    HandshakeRewrite rewrite = rewriteLoginHandshake(firstPacket, backendSourceIp);
                    clientTransformVersion = rewrite.transformVersion();
                    clientProtocolVersion = rewrite.protocolVersion();
                    clientChunkCacheVersion = rewrite.chunkCacheVersion();
                    OutputStream upstreamOut = upstream.getOutputStream();
                    if (firstPacket.length > 0) {
                        PacketIo.writePacket(upstreamOut, rewrite.packet());
                        stats.addRawUp(VarIntCodec.encode(rewrite.packet().length).length + rewrite.packet().length);
                    }
                    // 跨会话 manifest（v2，c2s）：客户端 advertise ccache>=2 时，会在握手后插入一个 ZNCM 帧列出它磁盘已持有的区块。
                    // 必须读掉它（不转发后端）以对齐流；自纠错：若该帧不以 ZNCM 起头（版本错配 / 客户端没发），则它其实是
                    // 真正的 c2s 包（如 login-start），原样转发后端，连接照常。
                    if (clientChunkCacheVersion >= ChunkCacheFormat.VERSION_MANIFEST) {
                        byte[] manifestFrame = PacketIo.readPacket(upstreamDecompressor, ChunkCacheFormat.MAX_MANIFEST_BYTES);
                        List<Hash128> parsed = ChunkManifest.parse(manifestFrame);
                        if (parsed == null) {
                            if (manifestFrame.length > 0) {
                                PacketIo.writePacket(upstreamOut, manifestFrame);
                                stats.addRawUp(VarIntCodec.encode(manifestFrame.length).length + manifestFrame.length);
                            }
                        } else if (cfg.chunkCache.engagesCache() && !parsed.isEmpty()) {
                            clientWarmSet = new HashSet<>(parsed);
                        }
                    }
                } catch (Exception handshakeErr) {
                    if (upstreamDecompressor != null) {
                        try {
                            upstreamDecompressor.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (isRealPipeErr(handshakeErr)) {
                        LOGGER.warn("[server] handshake read error source={}: {}", guardIp, handshakeErr.toString());
                    }
                    // 握手期间就断了（多半是后端崩溃 / 重启，发完握手前就掉线）：给登录态客户端补发压缩的真实原因。
                    // 到这里 clientMode 必为 ZSTD（RAW_STATUS/RAW_LOGIN 已在前面分流），字典也已协商，按 downstreamDict 压缩。
                    // 若实为客户端自身发了垃圾/已掉线，写失败会被静默吞掉，无副作用。s2c 泵尚未启动，可安全直写客户端。
                    sendCompressedLoginDisconnect(clientSocket, cfg, downstreamDict, BACKEND_UNAVAILABLE_MSG);
                    return;
                }

                // 下行区块引用缓存（chunk_cache=ref/full/auto）：需客户端 advertise ccache + 该协议有可缓存包表；字典连接跳过。
                final CacheablePacketTable cacheableTable =
                    (cfg.chunkCache.engagesCache() && clientChunkCacheVersion >= ChunkCacheFormat.VERSION_REF && !downstreamDict)
                        ? CacheablePacketTable.forProtocol(clientProtocolVersion)
                        : null;
                final boolean chunkCacheActive = cacheableTable != null && !cacheableTable.isEmpty();
                // 启用了 chunk_cache、客户端也 advertise，但本协议没有可缓存包表（未在 CacheablePacketTable 覆盖的协议版本）→ 该连接是 no-op。
                // 字节仍逐字节一致、绝不损坏，但运营者应知道这些客户端拿不到收益，故每协议告警一次。
                if (cfg.chunkCache.engagesCache() && clientChunkCacheVersion >= ChunkCacheFormat.VERSION_REF
                    && !downstreamDict && !chunkCacheActive
                    && chunkCacheUnsupportedWarned.add(clientProtocolVersion)) {
                    LOGGER.warn("[chunk_cache] enabled (mode={}) but no cacheable-packet table for MC protocol {} -> chunk cache is a NO-OP for these clients (bytes stay identical). Covered protocols: 758/760/763/767/775.",
                        cfg.chunkCache.configValue(), clientProtocolVersion);
                }
                // 协商出的生效 CRC 版本（写入 s2c PREAMBLE；>=2 才发 WARM_REF）。warm 集合仅在 v2 生效时携带。
                final int chunkCacheVersion = chunkCacheActive
                    ? Math.min(clientChunkCacheVersion, ChunkCacheFormat.MAX_SUPPORTED_VERSION)
                    : 0;
                final Set<Hash128> chunkWarm = chunkCacheVersion >= ChunkCacheFormat.VERSION_MANIFEST
                    ? clientWarmSet : Collections.emptySet();
                // AUTO 才启用自适应旁路（显式 ref/full 则确定性缓存，不旁路）。
                final int chunkCacheBypassWindow = cfg.chunkCache == CacheMode.AUTO
                    ? ChunkCacheFormat.DEFAULT_AUTO_BYPASS_WINDOW : 0;
                // 结构无关 PATCH（v3）：需生效版本>=3（客户端 advertise>=3）且 mode∈{FULL,AUTO}；ref 模式只发显式 REF。
                final boolean chunkCachePatch = chunkCacheVersion >= ChunkCacheFormat.VERSION_PATCH
                    && cfg.chunkCache.engagesPatch();
                // clientProtocolVersion 非 final（握手后赋值），lambda 捕获需快照（仅供 PATCH 选可选结构解析器）。
                final int chunkProtocolVersion = clientProtocolVersion;

                // 下行是否实体变换：服务端开关 + 客户端 advertise；字典连接跳过；CRC 生效时跳过（二者都重写帧流、互斥，CRC 优先）。
                boolean transformDownstream = cfg.transform.enabled()
                    && clientTransformVersion >= TransformFormat.VERSION_LAYER_A
                    && !downstreamDict
                    && !chunkCacheActive;
                final int transformVersion = transformDownstream
                    ? Math.min(clientTransformVersion, Math.min(cfg.transform.maxVersion(), TransformFormat.MAX_SUPPORTED_VERSION))
                    : 0;
                final boolean doTransformDownstream = transformVersion >= TransformFormat.VERSION_LAYER_A;
                // B1/B2 需按 MC 协议版本选实体包表；缺表时编码端自动退化为 Layer A（仍受益于去交错）。
                final EntityPacketTable transformTable = transformVersion >= TransformFormat.VERSION_B1
                    ? EntityPacketTable.forProtocol(clientProtocolVersion)
                    : null;
                // chunk_cache=measure：只读旁路埋点（不改任何字节），统计可引用缓存去重的重复流量。
                final ChunkCacheMeasurement.Collector chunkMeasure = cfg.chunkCache == CacheMode.MEASURE
                    ? chunkCacheMeasurement().newCollector(clientProtocolVersion)
                    : null;

                final InputStream c2sDecompressor = upstreamDecompressor;
                Future<Exception> c2s = workers.submit(() -> {
                    try {
                        pumpDecompress(upstream, c2sDecompressor, stats);
                        return null;
                    } catch (Exception ex) {
                        return ex;
                    } finally {
                        closeWrite(upstream);
                    }
                });

                Future<Exception> s2c = workers.submit(() -> {
                    try {
                        forwardCompress(clientSocket.getOutputStream(), upstream, cfg.level, cfg.flushInterval, stats, perConnLimiter, globalLimiter, cfg.compression, downstreamDict, doTransformDownstream, transformVersion, transformTable, chunkMeasure, chunkCacheActive, cacheableTable, chunkWarm, chunkCacheVersion, chunkCacheBypassWindow, chunkCachePatch, chunkProtocolVersion);
                        return null;
                    } catch (Exception ex) {
                        return ex;
                    } finally {
                        closeWrite(clientSocket);
                    }
                });

                Exception err1 = null;
                Exception err2 = null;
                while (!c2s.isDone() && !s2c.isDone()) {
                    Thread.sleep(20L);
                }

                if (c2s.isDone()) {
                    err1 = c2s.get();
                }
                if (s2c.isDone()) {
                    err2 = s2c.get();
                }

                // One direction ended: close both sockets so the other direction exits quickly too.
                closeSocket(clientSocket);
                closeSocket(upstream);

                if (!c2s.isDone()) {
                    err1 = c2s.get();
                }
                if (!s2c.isDone()) {
                    err2 = s2c.get();
                }
                if (isRealPipeErr(err1)) {
                    LOGGER.warn("pipe error source={} dir=client->mc: {}", guardIp, err1.toString());
                }
                if (isRealPipeErr(err2)) {
                    LOGGER.warn("pipe error source={} dir=mc->client: {}", guardIp, err2.toString());
                }
            } finally {
                guard.end(guardIp);
            }
        } catch (Exception ex) {
            if (isRealPipeErr(ex)) {
                LOGGER.warn("[server] connection error source={} remote={}: {}", guardIp, remoteIp, ex.toString());
            }
        } finally {
            stats.addConn(-1);
        }
    }

    private String resolveForwardedSourceIp(String remoteIp, ProxyInfo proxyInfo) {
        if (!cfg.trustProxyProtocol) {
            return remoteIp;
        }

        if (!isTrustedProxyPeer(remoteIp)) {
            LOGGER.warn(
                "[server] rejected PROXY protocol connection from untrusted peer {}. trusted_proxy_ips={}",
                remoteIp,
                String.join(",", cfg.trustedProxyIps)
            );
            return null;
        }

        if (!proxyInfo.valid || proxyInfo.sourceIp == null || proxyInfo.sourceIp.trim().isEmpty()) {
            LOGGER.warn("[server] rejected trusted PROXY protocol peer {} without a valid PROXY v2 header", remoteIp);
            return null;
        }

        LOGGER.debug("[server] trusted PROXY protocol source {} via {}", proxyInfo.sourceIp, remoteIp);
        return proxyInfo.sourceIp;
    }

    private boolean isTrustedProxyPeer(String remoteIp) {
        return remoteIp != null && cfg.trustedProxyIps.contains(remoteIp.trim());
    }

    /**
     * 客户端 -> 后端：zstd 解压后透传。
     */
    /**
     * Detect whether the public entry is receiving a raw status ping or a zstd-wrapped play/login stream.
     */
    private DetectedClientMode detectClientMode(PushbackInputStream in) throws IOException {
        byte[] firstPacketWire = tryReadPacketWire(in, 1500);
        if (firstPacketWire == null || firstPacketWire.length == 0) {
            return DetectedClientMode.zstd();
        }

        byte[] firstPacket = PacketIo.extractPacketPayload(firstPacketWire);
        Integer nextState = extractHandshakeNextState(firstPacket);
        if (nextState != null && nextState == 1) {
            byte[] secondPacketWire = tryReadPacketWire(in, 1500);
            if (secondPacketWire != null && secondPacketWire.length > 0) {
                byte[] secondPacket = PacketIo.extractPacketPayload(secondPacketWire);
                if (isStatusRequestPacket(secondPacket)) {
                    return new DetectedClientMode(
                        ClientMode.RAW_STATUS,
                        ByteArrayOps.concat(firstPacketWire, secondPacketWire)
                    );
                }
                in.unread(secondPacketWire);
            }
        } else if (nextState != null && nextState == 2) {
            byte[] secondPacketWire = tryReadPacketWire(in, 1500);
            if (secondPacketWire != null && secondPacketWire.length > 0) {
                byte[] secondPacket = PacketIo.extractPacketPayload(secondPacketWire);
                if (isLoginStartPacket(secondPacket)) {
                    return new DetectedClientMode(ClientMode.RAW_LOGIN, ByteArrayOps.concat(firstPacketWire, secondPacketWire));
                }
                in.unread(secondPacketWire);
            }
        }

        in.unread(firstPacketWire);
        return DetectedClientMode.zstd();
    }

    private void forwardRawPassthrough(
        Socket clientSocket,
        InputStream clientIn,
        Socket upstream,
        byte[] initialWireData,
        TrafficStats stats
    ) throws Exception {
        OutputStream upstreamOut = upstream.getOutputStream();
        upstreamOut.write(initialWireData);
        upstreamOut.flush();
        addRawPassthroughStats(initialWireData.length, stats, true);

        Future<?> upstreamWriter = workers.submit(() -> {
            try {
                streamRaw(clientIn, upstreamOut, stats, true);
            } catch (Exception ignored) {
            } finally {
                closeWrite(upstream);
            }
        });

        Future<?> downstreamWriter = workers.submit(() -> {
            try {
                streamRaw(upstream.getInputStream(), clientSocket.getOutputStream(), stats, false);
            } catch (Exception ignored) {
            } finally {
                closeWrite(clientSocket);
            }
        });

        upstreamWriter.get();
        downstreamWriter.get();
    }

    /**
     * Client -> backend: 持续解压 zstd 流并透传到后端。握手首包已在 {@link #handleClient} 中读出并转发，
     * 这里只负责其余字节的解压循环。
     */
    private void pumpDecompress(Socket dst, InputStream zstdIn, TrafficStats stats) throws IOException {
        try (InputStream zstdInResource = zstdIn) {
            OutputStream dstOut = dst.getOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = zstdIn.read(buf)) >= 0) {
                if (n > 0) {
                    dstOut.write(buf, 0, n);
                    stats.addRawUp(n);
                }
            }
        }
    }

    /**
     * 握手改写结果：改写后的握手包 + 客户端 advertise 的 transform 版本（0 表示无） +
     * 客户端 MC 协议版本号（用于选实体包表；0 表示未知/解析失败）。
     */
    private static final class HandshakeRewrite {
        private final byte[] packet;
        private final int transformVersion;
        private final int protocolVersion;
        private final int chunkCacheVersion;

        private HandshakeRewrite(byte[] packet, int transformVersion, int protocolVersion, int chunkCacheVersion) {
            this.packet = packet;
            this.transformVersion = transformVersion;
            this.protocolVersion = protocolVersion;
            this.chunkCacheVersion = chunkCacheVersion;
        }

        public byte[] packet() { return this.packet; }
        public int transformVersion() { return this.transformVersion; }
        public int protocolVersion() { return this.protocolVersion; }
        public int chunkCacheVersion() { return this.chunkCacheVersion; }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HandshakeRewrite)) {
                return false;
            }
            HandshakeRewrite other = (HandshakeRewrite) o;
            return Objects.equals(this.packet, other.packet)
                && this.transformVersion == other.transformVersion
                && this.protocolVersion == other.protocolVersion
                && this.chunkCacheVersion == other.chunkCacheVersion;
        }

        @Override
        public int hashCode() {
            return Objects.hash(packet, transformVersion, protocolVersion, chunkCacheVersion);
        }

        @Override
        public String toString() {
            return "HandshakeRewrite[packet=" + packet
                + ", transformVersion=" + transformVersion
                + ", protocolVersion=" + protocolVersion
                + ", chunkCacheVersion=" + chunkCacheVersion + "]";
        }
    }

    /**
     * 处理登录握手首包：解析 host，提取并剥离客户端 transform 能力标记（不让后端看到），
     * 再追加真实 IP 标记。非登录握手 / 解析失败时原样返回且版本为 0。
     */
    private HandshakeRewrite rewriteLoginHandshake(byte[] handshakePayload, String sourceIp) {
        if (handshakePayload == null || handshakePayload.length == 0) {
            return new HandshakeRewrite(handshakePayload, 0, 0, 0);
        }

        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0, handshakePayload.length);
        if (packetId == null || packetId.value() != 0) {
            return new HandshakeRewrite(handshakePayload, 0, 0, 0);
        }

        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next(), handshakePayload.length);
        if (protocol == null) {
            return new HandshakeRewrite(handshakePayload, 0, 0, 0);
        }

        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next(), handshakePayload.length);
        if (hostLength == null || hostLength.value() < 0) {
            return new HandshakeRewrite(handshakePayload, 0, 0, 0);
        }

        int hostStart = hostLength.next();
        int hostEnd = hostStart + hostLength.value();
        int portEnd = hostEnd + 2;
        if (portEnd > handshakePayload.length) {
            return new HandshakeRewrite(handshakePayload, 0, 0, 0);
        }

        VarIntRead nextState = VarIntCodec.read(handshakePayload, portEnd, handshakePayload.length);
        if (nextState == null || nextState.value() != 2) {
            return new HandshakeRewrite(handshakePayload, 0, 0, 0);
        }

        String originalHost = new String(handshakePayload, hostStart, hostLength.value(), StandardCharsets.UTF_8);
        int transformVersion = TransformHandshake.parseVersion(originalHost);
        int chunkCacheVersion = ChunkCacheHandshake.parseVersion(originalHost);
        String cleanedHost = ChunkCacheHandshake.strip(TransformHandshake.strip(originalHost));
        String forwardedHost = (sourceIp != null && !sourceIp.trim().isEmpty())
            ? appendForwardedIpMarker(cleanedHost, sourceIp)
            : cleanedHost;

        byte[] hostBytes = forwardedHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            // 多半是 real-ip 标记把 host 顶过 255。退回不带 real-ip、但仍剥净 zstdnet-* 标记的 cleanedHost 再改写，
            // 避免把 ccache/xform/real-ip 标记泄漏给后端。仅当连 cleanedHost 都超长（异常 host）才彻底放弃改写。
            byte[] cleanedBytes = cleanedHost.getBytes(StandardCharsets.UTF_8);
            if (cleanedBytes.length > 255) {
                LOGGER.warn("[zstdnet-server] skipped forwarded handshake host rewrite for {} because host is too long even after stripping markers: {} bytes", sourceIp, cleanedBytes.length);
                return new HandshakeRewrite(handshakePayload, transformVersion, protocol.value(), chunkCacheVersion);
            }
            LOGGER.warn("[zstdnet-server] forwarded handshake host too long ({} bytes) for {}; dropping real-ip marker but keeping zstdnet markers stripped", hostBytes.length, sourceIp);
            hostBytes = cleanedBytes;
        }

        if (sourceIp != null && !sourceIp.trim().isEmpty()) {
            LOGGER.info("[zstdnet-server] appended forwarded real IP {} to login handshake host '{}'", sourceIp, cleanedHost);
        }

        byte[] rebuilt = ByteArrayOps.concat(
            ByteArrayOps.slice(handshakePayload, 0, protocol.next()),
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            ByteArrayOps.slice(handshakePayload, hostEnd, handshakePayload.length)
        );
        return new HandshakeRewrite(rebuilt, transformVersion, protocol.value(), chunkCacheVersion);
    }

    private static String appendForwardedIpMarker(String originalHost, String sourceIp) {
        String marker = "zstdnet-real-ip=" + Base64.getUrlEncoder().withoutPadding().encodeToString(sourceIp.getBytes(StandardCharsets.UTF_8));
        int firstNull = originalHost.indexOf('\0');
        if (firstNull >= 0) {
            return originalHost + "\0" + marker;
        }

        int fmlMarker = originalHost.indexOf(" FML");
        if (fmlMarker >= 0) {
            String host = originalHost.substring(0, fmlMarker).trim();
            String version = originalHost.substring(fmlMarker + 1).trim();
            if (!host.isEmpty() && !version.isEmpty()) {
                return host + "\0" + version + "\0" + marker;
            }
        }

        return originalHost + "\0" + marker;
    }

    /**
     * 后端 -> 客户端：zstd 压缩后透传，并按配置执行 flush 与限速。
     */
    /**
     * Raw status traffic bypasses compression but still updates bandwidth stats as 100% ratio traffic.
     */
    private void streamRaw(InputStream in, OutputStream out, TrafficStats stats, boolean upstream) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                out.write(buf, 0, n);
                addRawPassthroughStats(n, stats, upstream);
            }
        }
    }

    /** 懒初始化 chunk_cache=measure 的全运行时埋点（用首次连接时的压缩配置确定假定匹配窗口）。 */
    private ChunkCacheMeasurement chunkCacheMeasurement() {
        ChunkCacheMeasurement m = chunkCacheMeasurement;
        if (m == null) {
            synchronized (this) {
                m = chunkCacheMeasurement;
                if (m == null) {
                    m = new ChunkCacheMeasurement(cfg.compression);
                    chunkCacheMeasurement = m;
                }
            }
        }
        return m;
    }

    /**
     * Backend -> client: compress outbound traffic with zstd and apply flush/rate controls.
     */
    private void forwardCompress(
        OutputStream dst,
        Socket src,
        int level,
        Duration flushInterval,
        TrafficStats stats,
        TokenBucketLimiter perConnLimiter,
        TokenBucketLimiter globalLimiter,
        CompressionOptions options,
        boolean useDictionary,
        boolean transform,
        int transformVersion,
        EntityPacketTable transformTable,
        ChunkCacheMeasurement.Collector chunkMeasure,
        boolean chunkCache,
        CacheablePacketTable cacheableTable,
        Set<Hash128> chunkWarm,
        int chunkCacheVersion,
        int chunkCacheBypassWindow,
        boolean chunkCachePatch,
        int chunkProtocolVersion
    ) throws IOException {
        OutputStream limitedDst = new RateLimitedOutputStream(dst, perConnLimiter, globalLimiter);
        DictionarySampler sampler = this.dictionarySampler;
        DictionarySampler.Collector sampleCollector = sampler != null ? sampler.newCollector() : null;
        OutputStream zstdOut = ZstdStreams.newCompressor(new CountingOutputStream(limitedDst, stats::addZstdDown), level, options, useDictionary);
        // 变换层 / CRC 层位于 ZSTD 之前。三者互斥（CRC 优先于实体变换）：
        //   chunkCache → 区块引用缓存（相同区块发 REF 令牌）；transform → 实体包去交错；都不开 → sink 即 zstdOut，行为与历史一致。
        // 关闭 sink 会级联落定剩余帧并关闭 zstdOut；跨 block 列匹配依赖 zstdOut 的连续帧（见 ZstdStreams）。
        OutputStream sink;
        if (chunkCache) {
            sink = new CacheTransformingOutputStream(zstdOut, cacheableTable, ChunkCacheFormat.DEFAULT_CACHE_BYTES,
                chunkWarm, chunkCacheVersion, chunkCacheBypassWindow, chunkCachePatch, chunkProtocolVersion);
        } else if (transform) {
            sink = new TransformingOutputStream(zstdOut, transformVersion, transformTable);
        } else {
            sink = zstdOut;
        }
        try (OutputStream sinkResource = sink) {
            InputStream srcIn = src.getInputStream();
            byte[] buf = new byte[16 * 1024];
            final long flushIntervalNs = Math.max(0L, flushInterval.toNanos());
            long lastFlushNs = System.nanoTime();
            int originalTimeout = src.getSoTimeout();
            int activeTimeout = originalTimeout;
            boolean hasPending = false;
            int n;
            try {
                while (true) {
                    if (flushIntervalNs > 0L) {
                        int desiredTimeout = originalTimeout;
                        if (hasPending) {
                            long elapsedNs = System.nanoTime() - lastFlushNs;
                            long remainingNs = Math.max(1L, flushIntervalNs - elapsedNs);
                            long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs));
                            long boundedMs = Math.min((long) Integer.MAX_VALUE, remainingMs);
                            desiredTimeout = (int) boundedMs;
                            if (originalTimeout > 0) {
                                desiredTimeout = Math.min(desiredTimeout, originalTimeout);
                            }
                        }
                        if (desiredTimeout != activeTimeout) {
                            src.setSoTimeout(desiredTimeout);
                            activeTimeout = desiredTimeout;
                        }
                    }

                    try {
                        n = srcIn.read(buf);
                    } catch (SocketTimeoutException timeout) {
                        if (flushIntervalNs > 0L && hasPending && (System.nanoTime() - lastFlushNs) >= flushIntervalNs) {
                            sink.flush();
                            hasPending = false;
                            lastFlushNs = System.nanoTime();
                        }
                        continue;
                    }

                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }

                    stats.addRawDown(n);
                    if (sampleCollector != null) {
                        sampleCollector.accept(buf, 0, n);
                    }
                    if (chunkMeasure != null) {
                        chunkMeasure.accept(buf, 0, n);
                    }
                    sink.write(buf, 0, n);
                    hasPending = true;
                    if (flushIntervalNs == 0L || (System.nanoTime() - lastFlushNs) >= flushIntervalNs) {
                        sink.flush();
                        hasPending = false;
                        lastFlushNs = System.nanoTime();
                    }
                }
            } finally {
                src.setSoTimeout(originalTimeout);
                if (sampleCollector != null) {
                    sampleCollector.finish();
                }
                if (chunkMeasure != null) {
                    chunkMeasure.finish();
                }
            }

            sink.flush();
        }
    }

    /**
     * 解析 PROXY protocol v2（仅处理 TCP over IPv4/IPv6）。
     */
    private ProxyInfo parseProxyProtocolV2(PushbackInputStream in) throws IOException {
        byte[] first = new byte[PROXY_V2_SIGNATURE.length];
        int n = readSome(in, first);
        if (n < 0) {
            return ProxyInfo.invalid();
        }
        if (n < PROXY_V2_SIGNATURE.length) {
            in.unread(first, 0, n);
            return ProxyInfo.invalid();
        }
        if (!Arrays.equals(first, PROXY_V2_SIGNATURE)) {
            in.unread(first);
            return ProxyInfo.invalid();
        }

        byte[] fixed = PacketIo.readFully(in, 4);
        int verCmd = fixed[0] & 0xFF;
        int famProto = fixed[1] & 0xFF;
        int payloadLen = ((fixed[2] & 0xFF) << 8) | (fixed[3] & 0xFF);
        if (payloadLen > MAX_PROXY_V2_PAYLOAD_SIZE) {
            throw new IOException("PROXY protocol v2 payload too large: " + payloadLen);
        }
        byte[] payload = PacketIo.readFully(in, payloadLen);

        int version = (verCmd & 0xF0) >> 4;
        int command = verCmd & 0x0F;
        int family = (famProto & 0xF0) >> 4;
        int protocol = famProto & 0x0F;

        if (version != 0x2 || command != 0x1 || protocol != 0x1) {
            return ProxyInfo.invalid();
        }

        if (family == 0x1 && payload.length >= 12) {
            String srcIp = ipString(payload, 0, 4);
            String dstIp = ipString(payload, 4, 4);
            int srcPort = u16(payload, 8);
            int dstPort = u16(payload, 10);
            return new ProxyInfo(true, srcIp, srcPort, dstIp, dstPort);
        }
        if (family == 0x2 && payload.length >= 36) {
            String srcIp = ipString(payload, 0, 16);
            String dstIp = ipString(payload, 16, 16);
            int srcPort = u16(payload, 32);
            int dstPort = u16(payload, 34);
            return new ProxyInfo(true, srcIp, srcPort, dstIp, dstPort);
        }
        return ProxyInfo.invalid();
    }

    /**
     * 尽量读取指定缓冲区，允许短读。
     */
    private int readSome(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off == 0 ? -1 : off;
            }
            off += n;
            if (n == 0) {
                break;
            }
        }
        return off;
    }

    /**
     * 按长度精确读取，长度不足抛 EOF。
     */
    /**
     * Read the first raw Minecraft packet if it looks small enough to be a handshake; otherwise leave the stream untouched.
     */
    private byte[] tryReadPacketWire(PushbackInputStream in, int maxWaitMillis) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(200L, maxWaitMillis);
        byte[] prefix = new byte[5];
        int prefixLength = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                int next = in.read();
                if (next < 0) {
                    if (prefixLength == 0) {
                        return new byte[0];
                    }
                    throw new EOFException("unexpected eof during packet read");
                }

                if (prefixLength >= prefix.length) {
                    throw new IOException("packet length varint too large");
                }

                prefix[prefixLength++] = (byte) next;
                VarIntRead packetLength = VarIntCodec.read(prefix, 0, prefixLength);
                if (packetLength == null) {
                    continue;
                }

                if (packetLength.value() <= 0 || packetLength.value() > MAX_HANDSHAKE_PACKET_SIZE) {
                    in.unread(prefix, 0, prefixLength);
                    return null;
                }

                byte[] payload = PacketIo.readFully(in, packetLength.value());
                byte[] packet = new byte[prefixLength + payload.length];
                System.arraycopy(prefix, 0, packet, 0, prefixLength);
                System.arraycopy(payload, 0, packet, prefixLength, payload.length);
                return packet;
            } catch (SocketTimeoutException ignored) {
            }
        }

        if (prefixLength > 0) {
            in.unread(prefix, 0, prefixLength);
        }
        return new byte[0];
    }

    private boolean isStatusRequestPacket(byte[] payload) {
        return payload != null && payload.length == 1 && payload[0] == 0;
    }

    private boolean isLoginStartPacket(byte[] payload) {
        if (payload == null || payload.length < 3) {
            return false;
        }

        VarIntRead packetId = VarIntCodec.read(payload, 0, payload.length);
        if (packetId == null || packetId.value() != 0) {
            return false;
        }

        VarIntRead nameLength = VarIntCodec.read(payload, packetId.next(), payload.length);
        if (nameLength == null || nameLength.value() < 1 || nameLength.value() > 16) {
            return false;
        }

        int nameStart = nameLength.next();
        int nameEnd = nameStart + nameLength.value();
        if (nameEnd > payload.length) {
            return false;
        }

        for (int i = nameStart; i < nameEnd; i++) {
            int ch = payload[i] & 0xFF;
            boolean valid = (ch >= '0' && ch <= '9')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || ch == '_';
            if (!valid) {
                return false;
            }
        }

        return true;
    }

    private Integer extractHandshakeNextState(byte[] handshakePayload) {
        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0, handshakePayload.length);
        if (packetId == null || packetId.value() != 0) {
            return null;
        }

        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next(), handshakePayload.length);
        if (protocol == null) {
            return null;
        }

        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next(), handshakePayload.length);
        if (hostLength == null || hostLength.value() < 0) {
            return null;
        }

        int afterHost = hostLength.next() + hostLength.value();
        int afterPort = afterHost + 2;
        if (afterPort > handshakePayload.length) {
            return null;
        }

        VarIntRead nextState = VarIntCodec.read(handshakePayload, afterPort, handshakePayload.length);
        if (nextState == null || (nextState.value() != 1 && nextState.value() != 2)) {
            return null;
        }
        return nextState.value();
    }

    private void sendLoginDisconnect(Socket clientSocket, String message) {
        if (clientSocket == null || message == null || message.trim().isEmpty()) {
            return;
        }

        try {
            // 登录态断开包编码集中在 core.protocol.LoginDisconnect（客户端本地代理也复用同一份）。
            if (!LoginDisconnect.trySend(clientSocket.getOutputStream(), message)) {
                LOGGER.debug("[server] failed to send raw-login disconnect packet");
            }
        } catch (IOException e) {
            LOGGER.debug("[server] failed to send raw-login disconnect packet: {}", e.toString());
        }
    }

    /**
     * 给仍处登录态的 <b>ZSTD</b> 客户端补发一条「压缩的」登录断开包，让玩家看到真实原因（如后端崩溃 / 不可用）。
     * <p>与明文版 {@link #sendLoginDisconnect} 的区别：ZSTD 客户端的下行整条走 zstd 解压，明文写过去会被当作非法帧
     * 解不出 → 客户端只能笼统判为「无响应」。故此处必须把断开包压成一个 zstd 帧再写。仅在确为 ZSTD 客户端时调用。
     *
     * @param useDictionary 是否按协商出的字典压缩（拨号阶段尚未协商时传 false）。
     */
    private void sendCompressedLoginDisconnect(Socket clientSocket, ProxyConfig cfg, boolean useDictionary, String message) {
        if (clientSocket == null || cfg == null || message == null || message.trim().isEmpty()) {
            return;
        }
        try {
            // 关闭 zstdOut 会写出帧尾并释放原生压缩上下文、同时关闭底层 socket 输出——本就要断开，正合适。
            try (OutputStream zstdOut = ZstdStreams.newCompressor(
                clientSocket.getOutputStream(), cfg.level, cfg.compression, useDictionary)) {
                zstdOut.write(LoginDisconnect.buildPacket(message));
                zstdOut.flush();
            }
        } catch (Exception e) {
            LOGGER.debug("[server] failed to send compressed login disconnect: {}", e.toString());
        }
    }

    private String ipString(byte[] data, int offset, int len) throws IOException {
        byte[] raw = Arrays.copyOfRange(data, offset, offset + len);
        return InetAddress.getByAddress(raw).getHostAddress();
    }

    private int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
     * 定时输出流量与连接统计，同时触发防刷状态清理。
     */
    /**
     * Utility helpers for packet framing and passthrough traffic accounting.
     */
    private void addRawPassthroughStats(int bytes, TrafficStats stats, boolean upstream) {
        if (stats == null || bytes <= 0) {
            return;
        }
        if (upstream) {
            stats.addRawUp(bytes);
            stats.addZstdUp(bytes);
        } else {
            stats.addRawDown(bytes);
            stats.addZstdDown(bytes);
        }
    }

    private HudSnapshot buildHudSnapshot(long rawUpRate, long rawDownRate, long zstdUpRate, long zstdDownRate) {
        long raw = stats.rawBytes();
        long zstd = stats.zstdBytes();
        double ratio = raw <= 0 ? 0.0 : ((double) zstd * 100.0 / (double) raw);
        return new HudSnapshot(
            runtimeMode,
            cfg.listen.host,
            cfg.listen.port,
            raw,
            zstd,
            stats.rawUpBytes(),
            stats.rawDownBytes(),
            stats.zstdUpBytes(),
            stats.zstdDownBytes(),
            rawUpRate,
            rawDownRate,
            zstdUpRate,
            zstdDownRate,
            rawUpRate + rawDownRate,
            zstdUpRate + zstdDownRate,
            ratio,
            stats.activeConnections()
        );
    }

    private void startStatsPrinter() {
        AtomicLong prevHudRawUp = new AtomicLong(stats.rawUpBytes.get());
        AtomicLong prevHudRawDown = new AtomicLong(stats.rawDownBytes.get());
        AtomicLong prevHudZstdUp = new AtomicLong(stats.zstdUpBytes.get());
        AtomicLong prevHudZstdDown = new AtomicLong(stats.zstdDownBytes.get());

        statsTicker.scheduleAtFixedRate(() -> {
            FloodGuard currentGuard = guard;
            if (currentGuard != null) {
                currentGuard.sweepExpired();
            }

            long rawUp = stats.rawUpBytes.get();
            long rawDown = stats.rawDownBytes.get();
            long zstdUp = stats.zstdUpBytes.get();
            long zstdDown = stats.zstdDownBytes.get();
            latestHudSnapshot = buildHudSnapshot(
                rawUp - prevHudRawUp.getAndSet(rawUp),
                rawDown - prevHudRawDown.getAndSet(rawDown),
                zstdUp - prevHudZstdUp.getAndSet(zstdUp),
                zstdDown - prevHudZstdDown.getAndSet(zstdDown)
            );
        }, 1000L, 1000L, TimeUnit.MILLISECONDS);

        if (cfg.statsInterval.isZero() || cfg.statsInterval.isNegative()) {
            LOGGER.info("[zstdnet-server] periodic stats logging disabled.");
            return;
        }

        long periodMs = Math.max(250L, cfg.statsInterval.toMillis());
        AtomicLong prevRaw = new AtomicLong(stats.rawBytes.get());
        AtomicLong prevZstd = new AtomicLong(stats.zstdBytes.get());

        statsTicker.scheduleAtFixedRate(() -> {
            long raw = stats.rawBytes.get();
            long zstd = stats.zstdBytes.get();

            long dr = raw - prevRaw.getAndSet(raw);
            long dz = zstd - prevZstd.getAndSet(zstd);
            long rawPerSec = (long) (dr * (1000.0 / periodMs));
            long zstdPerSec = (long) (dz * (1000.0 / periodMs));
            double ratio = raw <= 0 ? 0.0 : ((double) zstd * 100.0 / (double) raw);

            String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            LOGGER.info("[{}] Raw: {} ({}) | Zstd: {} ({}) | Ratio: {}% | Conns: {}",
                now,
                formatSize(raw),
                formatRate(rawPerSec),
                formatSize(zstd),
                formatRate(zstdPerSec),
                String.format(Locale.ROOT, "%.2f", ratio),
                stats.activeConnections());
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int idx = 0;
        while (v >= 1024.0 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[idx]);
    }

    private String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) {
            return bytesPerSec + "B/s";
        }
        String[] units = {"KB/s", "MB/s", "GB/s", "TB/s"};
        double v = bytesPerSec / 1024.0;
        int idx = 0;
        while (v >= 1024.0 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        return String.format(Locale.ROOT, "%.1f%s", v, units[idx]);
    }

    /**
     * 读取配置；若配置不存在则生成模板并提示用户编辑后重启。
     */
    private ProxyConfig loadOrCreateConfig(Path path, int mcServerPort, RuntimeMode mode) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                String body = buildDefaultConfig(mcServerPort, mode);
                Files.write(path, body.getBytes(StandardCharsets.UTF_8));
                LOGGER.info("[zstdnet-server] generated config: {}", path);
                LOGGER.info("[zstdnet-server] 已生成默认配置：专用服会保留 server.properties 里的 server-port 作为公网入口，自动改用本地空闲端口作为后端，并拒绝原版直连登录。");
            } catch (IOException e) {
                LOGGER.error("[zstdnet-server] failed to create config {}: {}", path, e.toString());
            }
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.error("[zstdnet-server] failed reading config {}: {}", path, e.toString());
            return null;
        }
        loadedConfigLastModified = readLastModified(path);

        boolean enabled = Boolean.parseBoolean(props.getProperty("enabled", "false").trim());
        boolean autoTakeover = Boolean.parseBoolean(props.getProperty("auto_takeover", "false").trim());
        HostPort listen = HostPort.parse(props.getProperty("listen", "0.0.0.0:" + DEFAULT_LISTEN_PORT));
        HostPort target = HostPort.parse(props.getProperty(
            "target",
            "127.0.0.1:" + DEFAULT_MINECRAFT_PORT
        ));
        boolean voiceChatPassthrough = Boolean.parseBoolean(props.getProperty("voice_chat_passthrough", "true").trim());
        String voiceChatListen = props.getProperty("voice_chat_listen", "").trim();
        String voiceChatTarget = props.getProperty("voice_chat_target", "").trim();
        String voiceTransport = props.getProperty("voice_transport", "tunnel").trim();
        String extraUdpPorts = props.getProperty("extra_udp_ports", "").trim();

        int level = clamp(parseInt(props.getProperty("level"), DEFAULT_ZSTD_LEVEL), 1, 22);
        int maxConn = parseInt(props.getProperty("max_conn_per_ip"), DEFAULT_MAX_CONN_PER_IP);
        int maxReq = parseInt(props.getProperty("max_req_per_window"), DEFAULT_MAX_REQ_PER_WINDOW);
        Duration window = parseDuration(props.getProperty("request_window"), Duration.ofSeconds(10));
        Duration ban = parseDuration(props.getProperty("ban_duration"), DEFAULT_BAN_DURATION);
        Duration statsInterval = parseDuration(props.getProperty("stats_interval"), Duration.ZERO);
        Duration flushInterval = parseDuration(props.getProperty("flush_interval"), Duration.ofMillis(2));
        Duration idleTimeout = parseDuration(props.getProperty("idle_timeout"), DEFAULT_IDLE_TIMEOUT);
        long maxRatePerConnBps = parseLong(props.getProperty("max_rate_per_conn_bps"), 0L);
        long maxRateGlobalBps = parseLong(props.getProperty("max_rate_global_bps"), 0L);
        int burstBytes = parseInt(props.getProperty("burst_bytes"), DEFAULT_BURST_BYTES);
        boolean trustProxyProtocol = Boolean.parseBoolean(props.getProperty("trust_proxy_protocol", "false").trim());
        Set<String> trustedProxyIps = parseTrustedProxyIps(props.getProperty("trusted_proxy_ips", DEFAULT_TRUSTED_PROXY_IPS));
        CompressionOptions compression = loadCompressionOptions(props, path);
        TransformOptions transform = loadTransformOptions(props);
        CacheMode chunkCache = CacheMode.parse(props.getProperty("chunk_cache", "auto"));
        setupDictionaryTooling(props, path);
        if (flushInterval.isNegative()) {
            flushInterval = Duration.ZERO;
        }
        if (idleTimeout.isNegative()) {
            idleTimeout = Duration.ZERO;
        }
        if (maxRatePerConnBps < 0) {
            maxRatePerConnBps = 0L;
        }
        if (maxRateGlobalBps < 0) {
            maxRateGlobalBps = 0L;
        }
        if (burstBytes <= 0) {
            burstBytes = DEFAULT_BURST_BYTES;
        }

        return new ProxyConfig(
            enabled,
            autoTakeover,
            listen,
            target,
            voiceChatPassthrough,
            voiceChatListen,
            voiceChatTarget,
            level,
            maxConn,
            maxReq,
            window,
            ban,
            statsInterval,
            flushInterval,
            idleTimeout,
            maxRatePerConnBps,
            maxRateGlobalBps,
            burstBytes,
            trustProxyProtocol,
            trustedProxyIps,
            compression,
            voiceTransport,
            extraUdpPorts,
            transform,
            chunkCache
        );
    }

    /**
     * 解析 LDM / 字典 等可选压缩参数。全部默认关闭——返回 {@link CompressionOptions#none()} 时
     * 压缩行为与历史版本逐字节一致。字典加载失败仅记日志并退回无字典，不影响启动。
     */
    private CompressionOptions loadCompressionOptions(Properties props, Path configPath) {
        boolean ldm = Boolean.parseBoolean(props.getProperty("long_distance_matching", "false").trim());
        int windowLog = parseInt(props.getProperty("window_log"), 0);

        byte[] dictionary = null;
        String dictName = ServerProxyConfigFile.resolveDictionaryName(props, configPath.getParent());
        if (!dictName.isEmpty()) {
            try {
                dictionary = DictionaryFiles.load(configPath.getParent(), dictName);
                if (dictionary != null) {
                    LOGGER.info(
                        "[zstdnet-server] loaded compression dictionary '{}' ({} bytes, id {})",
                        dictName,
                        dictionary.length,
                        ZstdCodecs.getDictIdFromDict(dictionary)
                    );
                }
            } catch (Exception ex) {
                LOGGER.error("[zstdnet-server] failed to load dictionary '{}'; continuing without it: {}", dictName, ex.toString());
                dictionary = null;
            }
        }

        CompressionOptions compression = CompressionOptions.of(ldm, windowLog, dictionary);
        if (compression.effectiveWindowLog() > CompressionOptions.DEFAULT_DECOMPRESS_WINDOW_LOG_MAX) {
            LOGGER.warn(
                "[zstdnet-server] window_log {} exceeds {}; connecting clients must enable a matching long_distance_matching/window_log or they will fail to decode the stream.",
                compression.effectiveWindowLog(),
                CompressionOptions.DEFAULT_DECOMPRESS_WINDOW_LOG_MAX
            );
        }
        return compression;
    }

    /**
     * 解析实体包流变换参数（{@code transform} / {@code transform_max_version} / {@code transform_coalesce_ms}）。
     * 默认关闭——返回 {@link TransformOptions#disabled()} 时下行不安装任何变换包装，与历史逐字节一致。
     */
    private TransformOptions loadTransformOptions(Properties props) {
        boolean enabled = Boolean.parseBoolean(props.getProperty("transform", "false").trim());
        if (!enabled) {
            return TransformOptions.disabled();
        }
        int maxVersion = parseInt(props.getProperty("transform_max_version"), TransformFormat.VERSION_B2);
        int coalesceMs = parseInt(props.getProperty("transform_coalesce_ms"), 0);
        return TransformOptions.enabled(maxVersion, coalesceMs);
    }

    /**
     * 配置字典制作工具：采样（{@code dictionary_capture}）与一次性训练（{@code dictionary_train}）。
     * 二者默认关闭，关闭时此方法不产生任何运行时开销。
     */
    private void setupDictionaryTooling(Properties props, Path configPath) {
        stopAutoDictWatcher();
        Path configDir = configPath.getParent();
        Path trainedDict = DictionaryFiles.dictDir(configDir).resolve(ServerProxyConfigFile.AUTO_TRAINED_DICT);

        boolean auto = Boolean.parseBoolean(props.getProperty("dictionary_auto", "false").trim());
        boolean autoTrained = auto && Files.isRegularFile(trainedDict);
        // dictionary_auto 且还没训练出字典 -> 自动进入「学习」：边采样边等够样本自动训练。
        // 已训练好则不再采样，直接用（见 loadCompressionOptions/resolveDictionaryName）。
        boolean autoLearning = auto && !autoTrained;

        boolean capture = autoLearning || Boolean.parseBoolean(props.getProperty("dictionary_capture", "false").trim());
        this.dictionarySampler = capture ? new DictionarySampler(DictionaryFiles.samplesDir(configDir)) : null;
        if (capture) {
            LOGGER.info("[zstdnet-server] dictionary sampling active; capturing connection-start traffic into {}", DictionaryFiles.samplesDir(configDir));
        }

        if (autoLearning) {
            startAutoDictWatcher(configDir, trainedDict);
            LOGGER.info("[zstdnet-server] dictionary_auto enabled: learning from live traffic; will auto-train after a few player connections, then enable and push the dictionary live (no restart, no disconnect of current sessions).");
        } else if (autoTrained) {
            LOGGER.info("[zstdnet-server] dictionary_auto: using auto-trained dictionary {}", trainedDict);
        }

        // 手动一次性训练（进阶用法，与 auto 互不冲突）。
        if (Boolean.parseBoolean(props.getProperty("dictionary_train", "false").trim())) {
            trainDictionaryFromSamples(configDir);
        }
    }

    /**
     * dictionary_auto 后台轮询器：样本攒够后在独立线程训练出 trained.dict，然后
     * {@link #applyAutoTrainedDictionary} 把字典<b>热插</b>进运行时（不断开任何在线连接），
     * 并置 {@link #dictionaryRolloutId} 让 per-variant tick 向所有在线玩家广播下发。
     */
    private void startAutoDictWatcher(Path configDir, Path trainedDict) {
        Path samplesDir = DictionaryFiles.samplesDir(configDir);
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(new NamedFactory("zstdsrv-autodict"));
        AtomicBoolean done = new AtomicBoolean(false);
        watcher.scheduleWithFixedDelay(() -> {
            if (done.get()) {
                return;
            }
            try {
                byte[] dictionary;
                if (Files.isRegularFile(trainedDict)) {
                    // 字典已存在（外部/并发生成）：直接热插启用。
                    dictionary = Files.readAllBytes(trainedDict);
                } else {
                    if (countSampleFiles(samplesDir) < AUTO_TRAIN_MIN_SAMPLES) {
                        return;
                    }
                    dictionary = DictionaryTrainer.train(samplesDir, AUTO_TRAIN_DICT_BYTES);
                    if (dictionary == null) {
                        // 样本暂不足以训出有效字典，继续等更多样本（不重启、不刷屏）。
                        return;
                    }
                    Files.createDirectories(trainedDict.getParent());
                    Files.write(trainedDict, dictionary);
                    LOGGER.info(
                        "[zstdnet-server] dictionary_auto: trained {} ({} bytes, id {}).",
                        trainedDict,
                        dictionary.length,
                        ZstdCodecs.getDictIdFromDict(dictionary)
                    );
                }
                done.set(true);
                applyAutoTrainedDictionary(dictionary);
            } catch (Throwable ex) {
                LOGGER.warn("[zstdnet-server] dictionary_auto watcher error: {}", ex.toString());
            }
        }, AUTO_TRAIN_POLL_SECONDS, AUTO_TRAIN_POLL_SECONDS, TimeUnit.SECONDS);
        this.autoDictWatcher = watcher;
    }

    /**
     * 把自动训练出的字典热插进运行时：替换 {@code cfg} 的压缩参数（仅影响<b>新</b>连接，在线连接照常不断），
     * 关闭采样，并置位 {@link #dictionaryRolloutId} 让上层向在线玩家广播下发。
     * 失败安全：任何异常都不影响现有转发。
     */
    private void applyAutoTrainedDictionary(byte[] dictionary) {
        synchronized (lifecycleLock) {
            if (!running || cfg == null || dictionary == null || dictionary.length == 0) {
                return;
            }
            CompressionOptions current = cfg.compression;
            CompressionOptions enabled = CompressionOptions.of(
                current.longDistanceMatching(),
                current.effectiveWindowLog(),
                dictionary
            );
            cfg = cfg.withCompression(enabled);
            dictionarySampler = null; // 已训练，停止采样
            dictionaryRolloutId = enabled.dictionaryId();
            LOGGER.info(
                "[zstdnet-server] dictionary_auto: enabled dictionary id {} live (no reconnects); announcing to online players.",
                enabled.dictionaryId()
            );
        }
    }

    private void stopAutoDictWatcher() {
        ScheduledExecutorService watcher = this.autoDictWatcher;
        this.autoDictWatcher = null;
        if (watcher != null) {
            watcher.shutdownNow();
        }
    }

    private static int countSampleFiles(Path samplesDir) {
        if (!Files.isDirectory(samplesDir)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(samplesDir, "*.bin")) {
            for (Path ignored : stream) {
                count++;
            }
        } catch (IOException ignored) {
            // 目录暂不可读时按当前计数返回，下一轮再试。
        }
        return count;
    }

    private void trainDictionaryFromSamples(Path configDir) {
        Path samplesDir = DictionaryFiles.samplesDir(configDir);
        try {
            byte[] dictionary = DictionaryTrainer.train(samplesDir, 112 * 1024);
            if (dictionary == null) {
                LOGGER.warn(
                    "[zstdnet-server] dictionary_train=true but not enough samples in {} yet. Enable dictionary_capture, let players connect, then try again.",
                    samplesDir
                );
                return;
            }
            Path out = DictionaryFiles.dictDir(configDir).resolve("trained.dict");
            Files.createDirectories(out.getParent());
            Files.write(out, dictionary);
            LOGGER.info(
                "[zstdnet-server] trained dictionary written to {} ({} bytes, id {}). Set dictionary=trained.dict and dictionary_train=false to use it, then restart.",
                out,
                dictionary.length,
                ZstdCodecs.getDictIdFromDict(dictionary)
            );
        } catch (Exception ex) {
            LOGGER.error("[zstdnet-server] dictionary training failed: {}", ex.toString());
        }
    }

    private Set<String> parseTrustedProxyIps(String raw) {
        LinkedHashSet<String> ips = new LinkedHashSet<>();
        String source = raw == null || raw.trim().isEmpty() ? DEFAULT_TRUSTED_PROXY_IPS : raw;
        for (String item : source.split(",")) {
            String value = item.trim();
            if (!value.isEmpty()) {
                ips.add(value);
            }
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
            ips.add("::1");
            ips.add("0:0:0:0:0:0:0:1");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(ips));
    }

    /**
     * 生成默认配置模板文本。
     */
    private String buildDefaultConfig(int mcServerPort, RuntimeMode mode) {
        if (mode == RuntimeMode.DEDICATED) {
            return buildDedicatedDefaultConfig();
        }

        return buildLanDefaultConfig(mcServerPort);
    }

    private String buildDedicatedDefaultConfig() {
        return ("# ------------------------------------------------------------\n"
            + "# zstdnet 内置服务端配置（自动生成）\n"
            + "# ------------------------------------------------------------\n"
            + "# 1) 专用服默认建议保持 auto_takeover=true。\n"
            + "# 2) auto_takeover=true 时，公网入口跟随 server.properties 里的 server-port。\n"
            + "# 3) 默认只透传原版状态查询，不透传原版登录。\n"
            + "# 4) listen / target 仅在高级手动模式下需要额外填写。\n"
            + "# 5) 地址不要写成 127.0.0.1.（末尾带点会解析失败）。\n"
            + "\n"
            + "# 是否启用内置 zstd 代理。\n"
            + "enabled=true\n"
            + "\n"
            + "# 是否自动接管 server.properties 里的 server-port 作为公网入口。\n"
            + "auto_takeover=true\n"
            + "\n"
            + "# 如需切回手动模式，可额外填写：\n"
            + "# listen=0.0.0.0:25565\n"
            + "# target=127.0.0.1:25566\n"
            + "\n"
            + "# Simple Voice Chat 的原样 UDP 转发。\n"
            + "voice_chat_passthrough=true\n"
            + "\n"
            + "# 语音聊天的公网 UDP 入口；LAN 默认跟随当前游戏 UDP 端口。\n"
            + "voice_chat_listen=0.0.0.0:${VOICE_PORT}\n"
            + "\n"
            + "# 语音聊天的后端 UDP 目标；LAN 默认指向当前游戏 UDP 端口。\n"
            + "voice_chat_target=127.0.0.1:${VOICE_PORT}\n"
            + "\n"
            + "# 语音/UDP 传输方式：tunnel=语音也走入口端口（只放行一个口）；bridge=语音直连真实服务器同端口。\n"
            + "voice_transport=tunnel\n"
            + "\n"
            + "# 额外透传的 UDP 端口（逗号分隔），用于自动探测覆盖不到的其它 UDP 模组。留空=仅自动探测。\n"
            + "extra_udp_ports=\n"
            + "\n"
            + "# zstd 压缩等级（1-22，通常建议 3-9）。\n"
            + "level=${LEVEL}\n"
            + "\n"
            + "# 单个 IP 最大并发连接数（<=0 表示关闭限制）。\n"
            + "max_conn_per_ip=${MAX_CONN}\n"
            + "\n"
            + "# 单个 IP 在 request_window 内最大请求次数（<=0 表示关闭限制）。\n"
            + "max_req_per_window=${MAX_REQ}\n"
            + "\n"
            + "# 请求计数时间窗口。\n"
            + "request_window=10s\n"
            + "\n"
            + "# 超限后的封禁时长。\n"
            + "ban_duration=1m\n"
            + "\n"
            + "# 统计日志输出间隔。\n"
            + "stats_interval=0s\n"
            + "\n"
            + "# zstd flush 间隔，0ms 表示每次写入都 flush。\n"
            + "flush_interval=2ms\n"
            + "\n"
            + "# 后端读取空闲超时，0 表示关闭。\n"
            + "idle_timeout=0\n"
            + "\n"
            + "# 单连接限速（字节/秒，0 表示关闭）。\n"
            + "max_rate_per_conn_bps=0\n"
            + "\n"
            + "# 全局总限速（字节/秒，0 表示关闭）。\n"
            + "max_rate_global_bps=0\n"
            + "\n"
            + "# 令牌桶突发容量（字节）。\n"
            + "burst_bytes=${BURST_BYTES}\n"
            + "\n"
            + "# 如果玩家是通过 frp / 反代进服，并且你想让后端看到玩家真实 IP，就改成 true。\n"
            + "# 普通直连、本机测试、局域网、公网直连都保持 false。\n"
            + "# 改成 true 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝。\n"
            + "trust_proxy_protocol=false\n"
            + "\n"
            + "# 允许哪些机器转发“玩家真实 IP”给 zstdnet。\n"
            + "# frpc 和服务端在同一台机器时不用改，保持 127.0.0.1 即可。\n"
            + "# 如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 IP。\n"
            + "trusted_proxy_ips=${TRUSTED_PROXY_IPS}\n")
            .replace("${LEVEL}", String.valueOf(DEFAULT_ZSTD_LEVEL))
            .replace("${MAX_CONN}", String.valueOf(DEFAULT_MAX_CONN_PER_IP))
            .replace("${MAX_REQ}", String.valueOf(DEFAULT_MAX_REQ_PER_WINDOW))
            .replace("${BURST_BYTES}", String.valueOf(DEFAULT_BURST_BYTES))
            .replace("${TRUSTED_PROXY_IPS}", DEFAULT_TRUSTED_PROXY_IPS);
    }

    private String buildLanDefaultConfig(int mcServerPort) {
        return ("# ------------------------------------------------------------\n"
            + "# zstdnet 内置服务端配置（自动生成）\n"
            + "# ------------------------------------------------------------\n"
            + "# 1) 这个模板主要用于单机开房 / 局域网转发场景。\n"
            + "# 2) 这里会直接写出当前 zstd 入口和后端游戏端口，方便房主查看和调整。\n"
            + "# 3) 默认只透传原版状态查询，不透传原版登录。\n"
            + "# 4) 地址不要写成 127.0.0.1.（末尾带点会解析失败）。\n"
            + "\n"
            + "# 是否启用内置 zstd 代理。\n"
            + "enabled=true\n"
            + "\n"
            + "# 是否自动接管当前公开的游戏端口作为公网入口。\n"
            + "auto_takeover=true\n"
            + "\n"
            + "# zstd 公网监听入口。\n"
            + "# 单机 / 局域网场景下，这里通常就是当前对外分享的 zstd 端口。\n"
            + "listen=0.0.0.0:${LISTEN_PORT}\n"
            + "\n"
            + "# 后端 Minecraft 地址。\n"
            + "# 一般保持为本地游戏端口即可。\n"
            + "target=127.0.0.1:${TARGET_PORT}\n"
            + "\n"
            + "# Simple Voice Chat 的原样 UDP 转发。\n"
            + "voice_chat_passthrough=true\n"
            + "\n"
            + "# 语音聊天的公网 UDP 入口；留空时跟随当前 LAN 端口，填写后按配置值生效。\n"
            + "voice_chat_listen=\n"
            + "\n"
            + "# 语音聊天的后端 UDP 目标；留空时指向本机当前 LAN 端口，填写后按配置值生效。\n"
            + "voice_chat_target=\n"
            + "\n"
            + "# 语音/UDP 传输方式：tunnel=语音也走入口端口（只放行一个口）；bridge=语音直连真实服务器同端口。\n"
            + "voice_transport=tunnel\n"
            + "\n"
            + "# 额外透传的 UDP 端口（逗号分隔），用于自动探测覆盖不到的其它 UDP 模组。留空=仅自动探测。\n"
            + "extra_udp_ports=\n"
            + "\n"
            + "# zstd 压缩等级（1-22，通常建议 3-9）。\n"
            + "level=${LEVEL}\n"
            + "\n"
            + "# 单个 IP 最大并发连接数（<=0 表示关闭限制）。\n"
            + "max_conn_per_ip=${MAX_CONN}\n"
            + "\n"
            + "# 单个 IP 在 request_window 内最大请求次数（<=0 表示关闭限制）。\n"
            + "max_req_per_window=${MAX_REQ}\n"
            + "\n"
            + "# 请求计数时间窗口。\n"
            + "request_window=10s\n"
            + "\n"
            + "# 超限后的封禁时长。\n"
            + "ban_duration=1m\n"
            + "\n"
            + "# 统计日志输出间隔。\n"
            + "stats_interval=0s\n"
            + "\n"
            + "# zstd flush 间隔，0ms 表示每次写入都 flush。\n"
            + "flush_interval=2ms\n"
            + "\n"
            + "# 后端读取空闲超时，0 表示关闭。\n"
            + "idle_timeout=0\n"
            + "\n"
            + "# 单连接限速（字节/秒，0 表示关闭）。\n"
            + "max_rate_per_conn_bps=0\n"
            + "\n"
            + "# 全局总限速（字节/秒，0 表示关闭）。\n"
            + "max_rate_global_bps=0\n"
            + "\n"
            + "# 令牌桶突发容量（字节）。\n"
            + "burst_bytes=${BURST_BYTES}\n"
            + "\n"
            + "# 如果玩家是通过 frp / 反代进服，并且你想让后端看到玩家真实 IP，就改成 true。\n"
            + "# 普通直连、本机测试、局域网、公网直连都保持 false。\n"
            + "# 改成 true 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝。\n"
            + "trust_proxy_protocol=false\n"
            + "\n"
            + "# 允许哪些机器转发“玩家真实 IP”给 zstdnet。\n"
            + "# frpc 和服务端在同一台机器时不用改，保持 127.0.0.1 即可。\n"
            + "# 如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 IP。\n"
            + "trusted_proxy_ips=${TRUSTED_PROXY_IPS}\n")
            .replace("${LISTEN_PORT}", String.valueOf(mcServerPort))
            .replace("${TARGET_PORT}", String.valueOf(defaultAutoTargetPort(mcServerPort)))
            .replace("${VOICE_PORT}", String.valueOf(mcServerPort))
            .replace("${LEVEL}", String.valueOf(DEFAULT_ZSTD_LEVEL))
            .replace("${MAX_CONN}", String.valueOf(DEFAULT_MAX_CONN_PER_IP))
            .replace("${MAX_REQ}", String.valueOf(DEFAULT_MAX_REQ_PER_WINDOW))
            .replace("${BURST_BYTES}", String.valueOf(DEFAULT_BURST_BYTES))
            .replace("${TRUSTED_PROXY_IPS}", DEFAULT_TRUSTED_PROXY_IPS);
    }

    private int defaultAutoTargetPort(int publicPort) {
        int candidate = publicPort + 1;
        if (candidate <= 0 || candidate > 65535) {
            return DEFAULT_MINECRAFT_PORT + 1;
        }
        return candidate;
    }

    private VoiceChatPassthroughDecision resolveVoiceChatPassthrough(ProxyConfig config) {
        Path svcConfig = simpleVoiceChatConfigPath();
        Integer simpleVoiceChatPort = readSimpleVoiceChatPort(svcConfig);
        return resolveVoiceChatPassthrough(
            config.listen,
            config.target,
            config.voiceChatPassthrough,
            config.voiceChatListen,
            config.voiceChatTarget,
            simpleVoiceChatPort,
            svcConfig.toString()
        );
    }

    static VoiceChatPassthroughDecision resolveVoiceChatPassthrough(
        HostPort gameListen,
        HostPort gameTarget,
        boolean voiceChatPassthrough,
        String configuredListen,
        String configuredTarget,
        Integer simpleVoiceChatPort,
        String simpleVoiceChatSource
    ) {
        if (!voiceChatPassthrough) {
            return VoiceChatPassthroughDecision.disabled("voice chat UDP passthrough disabled by config");
        }
        if (gameListen == null || gameTarget == null) {
            return VoiceChatPassthroughDecision.disabled("game UDP route is unavailable");
        }

        boolean samePortMode = simpleVoiceChatPort != null && simpleVoiceChatPort == -1;
        HostPort target;
        try {
            target = parseOptionalHostPort(configuredTarget);
        } catch (IllegalArgumentException e) {
            return VoiceChatPassthroughDecision.disabled("invalid voice_chat_target: " + e.getMessage());
        }
        if (target == null) {
            if (simpleVoiceChatPort == null) {
                return VoiceChatPassthroughDecision.disabled(
                    "voice_chat_target is blank and Simple Voice Chat config was not found at " + simpleVoiceChatSource
                );
            }
            if (samePortMode) {
                target = gameTarget;
            } else if (simpleVoiceChatPort >= 1 && simpleVoiceChatPort <= 65535) {
                target = new HostPort("127.0.0.1", simpleVoiceChatPort);
            } else {
                return VoiceChatPassthroughDecision.disabled("invalid Simple Voice Chat port " + simpleVoiceChatPort);
            }
        }

        HostPort listen;
        try {
            listen = parseOptionalHostPort(configuredListen);
        } catch (IllegalArgumentException e) {
            return VoiceChatPassthroughDecision.disabled("invalid voice_chat_listen: " + e.getMessage());
        }
        if (listen == null) {
            if (simpleVoiceChatPort == null) {
                listen = new HostPort(gameListen.host(), target.port());
            } else if (samePortMode) {
                listen = gameListen;
            } else {
                return VoiceChatPassthroughDecision.disabled(
                    "Simple Voice Chat uses a separate port, so voice_chat_listen must be set explicitly"
                );
            }
        }

        if (listen.equals(gameListen) && target.equals(gameTarget)) {
            return VoiceChatPassthroughDecision.reuseGameRoute("voice chat UDP reuses the built-in game UDP route");
        }

        if (listen.port() == target.port() && isLocalHost(target.host())) {
            HostPort adjustedListen = chooseAlternateVoiceChatListen(listen, target);
            if (!adjustedListen.equals(listen)) {
                LOGGER.warn(
                    "[zstdnet-server] voice chat listen {} collides with local target {}; using {} instead.",
                    listen,
                    target,
                    adjustedListen
                );
                listen = adjustedListen;
            }
        }

        return VoiceChatPassthroughDecision.route(new UdpRoute("simple_voice_chat", listen, target));
    }

    private static HostPort chooseAlternateVoiceChatListen(HostPort listen, HostPort target) {
        int preferred = target.port() >= MAX_PORT ? MIN_PORT : target.port() + 1;
        int port = findFreeUdpPort(listen.host(), preferred, listen.port(), target.port());
        return port == listen.port() ? listen : new HostPort(listen.host(), port);
    }

    private static int findFreeUdpPort(String host, int preferredPort, int... reservedPorts) {
        String hostToProbe = host == null || host.trim().isEmpty() ? DEFAULT_LISTEN_HOST : host.trim();
        int start = Math.max(MIN_PORT, Math.min(MAX_PORT, preferredPort));

        for (int port = start; port <= MAX_PORT; port++) {
            if (!isReservedPort(port, reservedPorts) && isUdpBindable(hostToProbe, port)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < start; port++) {
            if (!isReservedPort(port, reservedPorts) && isUdpBindable(hostToProbe, port)) {
                return port;
            }
        }

        throw new IllegalStateException("no free UDP port available for voice chat passthrough");
    }

    private static boolean isReservedPort(int port, int... reservedPorts) {
        if (reservedPorts == null) {
            return false;
        }
        for (int reserved : reservedPorts) {
            if (reserved == port) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUdpBindable(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            InetSocketAddress address;
            if (host == null || host.trim().isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)) {
                address = new InetSocketAddress(port);
            } else {
                address = new InetSocketAddress(InetAddress.getByName(host), port);
            }
            socket.bind(address);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
            || normalized.equals("127.0.0.1")
            || normalized.startsWith("127.")
            || normalized.equals("::1")
            || normalized.equals("0.0.0.0")
            || normalized.equals("::");
    }

    private Integer readSimpleVoiceChatPort(Path configPath) {
        if (configPath == null || !Files.exists(configPath)) {
            return null;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed reading Simple Voice Chat config {}: {}", configPath, e.toString());
            return null;
        }
        String rawPort = props.getProperty("port");
        Integer parsedPort = parseSimpleVoiceChatPort(rawPort);
        if (parsedPort == null && rawPort != null && !rawPort.trim().isEmpty()) {
            LOGGER.warn("[zstdnet-server] invalid Simple Voice Chat port '{}' in {}", rawPort, configPath);
        }
        return parsedPort;
    }

    static Integer parseSimpleVoiceChatPort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static HostPort parseOptionalHostPort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return HostPort.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(raw.trim(), e);
        }
    }

    private static Path simpleVoiceChatConfigPath() {
        return Platforms.get().configDir().resolve("voicechat").resolve("voicechat-server.properties");
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            }
            if (text.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applyReadTimeout(Socket socket, Duration timeout) {
        if (socket == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        long timeoutMs = Math.max(1L, timeout.toMillis());
        int bounded = (int) Math.min((long) Integer.MAX_VALUE, timeoutMs);
        try {
            socket.setSoTimeout(bounded);
        } catch (Exception ignored) {
        }
    }

    private String sourceIp(SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) address;
            InetAddress ip = inet.getAddress();
            return ip != null ? ip.getHostAddress() : inet.getHostString();
        }
        return String.valueOf(address);
    }

    private boolean isRealPipeErr(Exception err) {
        if (err == null || err instanceof EOFException) {
            return false;
        }
        String msg = err.toString().toLowerCase(Locale.ROOT);
        return !(msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("socket closed"));
    }

    private void closeWrite(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private void closeSocket(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(ServerSocket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void shutdownQuietly(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
    }

    private long readLastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * PROXY protocol 解析结果模块。
     */
    private static final class ProxyInfo {
        private final boolean valid;
        private final String sourceIp;
        private final int sourcePort;
        private final String targetIp;
        private final int targetPort;

        private ProxyInfo(boolean valid, String sourceIp, int sourcePort, String targetIp, int targetPort) {
            this.valid = valid;
            this.sourceIp = sourceIp;
            this.sourcePort = sourcePort;
            this.targetIp = targetIp;
            this.targetPort = targetPort;
        }

        public boolean valid() { return this.valid; }
        public String sourceIp() { return this.sourceIp; }
        public int sourcePort() { return this.sourcePort; }
        public String targetIp() { return this.targetIp; }
        public int targetPort() { return this.targetPort; }

        static ProxyInfo invalid() {
            return new ProxyInfo(false, null, 0, null, 0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ProxyInfo)) {
                return false;
            }
            ProxyInfo other = (ProxyInfo) o;
            return this.valid == other.valid
                && this.sourcePort == other.sourcePort
                && this.targetPort == other.targetPort
                && Objects.equals(this.sourceIp, other.sourceIp)
                && Objects.equals(this.targetIp, other.targetIp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(valid, sourceIp, sourcePort, targetIp, targetPort);
        }

        @Override
        public String toString() {
            return "ProxyInfo[valid=" + valid
                + ", sourceIp=" + sourceIp
                + ", sourcePort=" + sourcePort
                + ", targetIp=" + targetIp
                + ", targetPort=" + targetPort + "]";
        }
    }

    private static final class BindResult {
        private final ServerSocket listener;
        private final ProxyConfig config;

        private BindResult(ServerSocket listener, ProxyConfig config) {
            this.listener = listener;
            this.config = config;
        }

        public ServerSocket listener() { return this.listener; }
        public ProxyConfig config() { return this.config; }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BindResult)) {
                return false;
            }
            BindResult other = (BindResult) o;
            return Objects.equals(this.listener, other.listener)
                && Objects.equals(this.config, other.config);
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener, config);
        }

        @Override
        public String toString() {
            return "BindResult[listener=" + listener + ", config=" + config + "]";
        }
    }

    /**
     * 服务端配置快照模块。
     */
    private enum ClientMode {
        ZSTD,
        RAW_STATUS,
        RAW_LOGIN
    }

    private enum RuntimeMode {
        DEDICATED,
        LAN
    }

    static final class HudSnapshot {
        private final RuntimeMode mode;
        private final String listenHost;
        private final int listenPort;
        private final long rawBytes;
        private final long zstdBytes;
        private final long rawUpBytes;
        private final long rawDownBytes;
        private final long zstdUpBytes;
        private final long zstdDownBytes;
        private final long rawUpRate;
        private final long rawDownRate;
        private final long zstdUpRate;
        private final long zstdDownRate;
        private final long rawRate;
        private final long zstdRate;
        private final double ratioPercent;
        private final int connections;

        HudSnapshot(
            RuntimeMode mode,
            String listenHost,
            int listenPort,
            long rawBytes,
            long zstdBytes,
            long rawUpBytes,
            long rawDownBytes,
            long zstdUpBytes,
            long zstdDownBytes,
            long rawUpRate,
            long rawDownRate,
            long zstdUpRate,
            long zstdDownRate,
            long rawRate,
            long zstdRate,
            double ratioPercent,
            int connections
        ) {
            this.mode = mode;
            this.listenHost = listenHost;
            this.listenPort = listenPort;
            this.rawBytes = rawBytes;
            this.zstdBytes = zstdBytes;
            this.rawUpBytes = rawUpBytes;
            this.rawDownBytes = rawDownBytes;
            this.zstdUpBytes = zstdUpBytes;
            this.zstdDownBytes = zstdDownBytes;
            this.rawUpRate = rawUpRate;
            this.rawDownRate = rawDownRate;
            this.zstdUpRate = zstdUpRate;
            this.zstdDownRate = zstdDownRate;
            this.rawRate = rawRate;
            this.zstdRate = zstdRate;
            this.ratioPercent = ratioPercent;
            this.connections = connections;
        }

        public RuntimeMode mode() { return this.mode; }
        public String listenHost() { return this.listenHost; }
        public int listenPort() { return this.listenPort; }
        public long rawBytes() { return this.rawBytes; }
        public long zstdBytes() { return this.zstdBytes; }
        public long rawUpBytes() { return this.rawUpBytes; }
        public long rawDownBytes() { return this.rawDownBytes; }
        public long zstdUpBytes() { return this.zstdUpBytes; }
        public long zstdDownBytes() { return this.zstdDownBytes; }
        public long rawUpRate() { return this.rawUpRate; }
        public long rawDownRate() { return this.rawDownRate; }
        public long zstdUpRate() { return this.zstdUpRate; }
        public long zstdDownRate() { return this.zstdDownRate; }
        public long rawRate() { return this.rawRate; }
        public long zstdRate() { return this.zstdRate; }
        public double ratioPercent() { return this.ratioPercent; }
        public int connections() { return this.connections; }

        String modeName() {
            return mode.name();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HudSnapshot)) {
                return false;
            }
            HudSnapshot other = (HudSnapshot) o;
            return this.listenPort == other.listenPort
                && this.rawBytes == other.rawBytes
                && this.zstdBytes == other.zstdBytes
                && this.rawUpBytes == other.rawUpBytes
                && this.rawDownBytes == other.rawDownBytes
                && this.zstdUpBytes == other.zstdUpBytes
                && this.zstdDownBytes == other.zstdDownBytes
                && this.rawUpRate == other.rawUpRate
                && this.rawDownRate == other.rawDownRate
                && this.zstdUpRate == other.zstdUpRate
                && this.zstdDownRate == other.zstdDownRate
                && this.rawRate == other.rawRate
                && this.zstdRate == other.zstdRate
                && Double.compare(this.ratioPercent, other.ratioPercent) == 0
                && this.connections == other.connections
                && this.mode == other.mode
                && Objects.equals(this.listenHost, other.listenHost);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, listenHost, listenPort, rawBytes, zstdBytes, rawUpBytes, rawDownBytes,
                zstdUpBytes, zstdDownBytes, rawUpRate, rawDownRate, zstdUpRate, zstdDownRate, rawRate, zstdRate,
                ratioPercent, connections);
        }

        @Override
        public String toString() {
            return "HudSnapshot[mode=" + mode
                + ", listenHost=" + listenHost
                + ", listenPort=" + listenPort
                + ", rawBytes=" + rawBytes
                + ", zstdBytes=" + zstdBytes
                + ", rawUpBytes=" + rawUpBytes
                + ", rawDownBytes=" + rawDownBytes
                + ", zstdUpBytes=" + zstdUpBytes
                + ", zstdDownBytes=" + zstdDownBytes
                + ", rawUpRate=" + rawUpRate
                + ", rawDownRate=" + rawDownRate
                + ", zstdUpRate=" + zstdUpRate
                + ", zstdDownRate=" + zstdDownRate
                + ", rawRate=" + rawRate
                + ", zstdRate=" + zstdRate
                + ", ratioPercent=" + ratioPercent
                + ", connections=" + connections + "]";
        }
    }

    private static final class DetectedClientMode {
        private final ClientMode mode;
        private final byte[] initialWireData;

        private DetectedClientMode(ClientMode mode, byte[] initialWireData) {
            this.mode = mode;
            this.initialWireData = initialWireData;
        }

        public ClientMode mode() { return this.mode; }
        public byte[] initialWireData() { return this.initialWireData; }

        private static DetectedClientMode zstd() {
            return new DetectedClientMode(ClientMode.ZSTD, null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DetectedClientMode)) {
                return false;
            }
            DetectedClientMode other = (DetectedClientMode) o;
            return this.mode == other.mode
                && Objects.equals(this.initialWireData, other.initialWireData);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, initialWireData);
        }

        @Override
        public String toString() {
            return "DetectedClientMode[mode=" + mode + ", initialWireData=" + initialWireData + "]";
        }
    }

    private static final class ProxyConfig {
        private final boolean enabled;
        private final boolean autoTakeover;
        private final HostPort listen;
        private final HostPort target;
        private final boolean voiceChatPassthrough;
        private final String voiceChatListen;
        private final String voiceChatTarget;
        private final int level;
        private final int maxConnPerIp;
        private final int maxReqPerWindow;
        private final Duration window;
        private final Duration banDuration;
        private final Duration statsInterval;
        private final Duration flushInterval;
        private final Duration idleTimeout;
        private final long maxRatePerConnBps;
        private final long maxRateGlobalBps;
        private final int burstBytes;
        private final boolean trustProxyProtocol;
        private final Set<String> trustedProxyIps;
        private final CompressionOptions compression;
        private final String voiceTransport;
        private final String extraUdpPorts;
        private final TransformOptions transform;
        private final CacheMode chunkCache;

        private ProxyConfig(
            boolean enabled,
            boolean autoTakeover,
            HostPort listen,
            HostPort target,
            boolean voiceChatPassthrough,
            String voiceChatListen,
            String voiceChatTarget,
            int level,
            int maxConnPerIp,
            int maxReqPerWindow,
            Duration window,
            Duration banDuration,
            Duration statsInterval,
            Duration flushInterval,
            Duration idleTimeout,
            long maxRatePerConnBps,
            long maxRateGlobalBps,
            int burstBytes,
            boolean trustProxyProtocol,
            Set<String> trustedProxyIps,
            CompressionOptions compression,
            String voiceTransport,
            String extraUdpPorts,
            TransformOptions transform,
            CacheMode chunkCache
        ) {
            this.enabled = enabled;
            this.autoTakeover = autoTakeover;
            this.listen = listen;
            this.target = target;
            this.voiceChatPassthrough = voiceChatPassthrough;
            this.voiceChatListen = voiceChatListen;
            this.voiceChatTarget = voiceChatTarget;
            this.level = level;
            this.maxConnPerIp = maxConnPerIp;
            this.maxReqPerWindow = maxReqPerWindow;
            this.window = window;
            this.banDuration = banDuration;
            this.statsInterval = statsInterval;
            this.flushInterval = flushInterval;
            this.idleTimeout = idleTimeout;
            this.maxRatePerConnBps = maxRatePerConnBps;
            this.maxRateGlobalBps = maxRateGlobalBps;
            this.burstBytes = burstBytes;
            this.trustProxyProtocol = trustProxyProtocol;
            this.trustedProxyIps = trustedProxyIps;
            this.compression = compression;
            this.voiceTransport = voiceTransport;
            this.extraUdpPorts = extraUdpPorts;
            this.transform = transform;
            this.chunkCache = chunkCache;
        }

        public boolean enabled() { return this.enabled; }
        public boolean autoTakeover() { return this.autoTakeover; }
        public HostPort listen() { return this.listen; }
        public HostPort target() { return this.target; }
        public boolean voiceChatPassthrough() { return this.voiceChatPassthrough; }
        public String voiceChatListen() { return this.voiceChatListen; }
        public String voiceChatTarget() { return this.voiceChatTarget; }
        public int level() { return this.level; }
        public int maxConnPerIp() { return this.maxConnPerIp; }
        public int maxReqPerWindow() { return this.maxReqPerWindow; }
        public Duration window() { return this.window; }
        public Duration banDuration() { return this.banDuration; }
        public Duration statsInterval() { return this.statsInterval; }
        public Duration flushInterval() { return this.flushInterval; }
        public Duration idleTimeout() { return this.idleTimeout; }
        public long maxRatePerConnBps() { return this.maxRatePerConnBps; }
        public long maxRateGlobalBps() { return this.maxRateGlobalBps; }
        public int burstBytes() { return this.burstBytes; }
        public boolean trustProxyProtocol() { return this.trustProxyProtocol; }
        public Set<String> trustedProxyIps() { return this.trustedProxyIps; }
        public CompressionOptions compression() { return this.compression; }
        public String voiceTransport() { return this.voiceTransport; }
        public String extraUdpPorts() { return this.extraUdpPorts; }
        public TransformOptions transform() { return this.transform; }
        public CacheMode chunkCache() { return this.chunkCache; }

        private ProxyConfig withTarget(HostPort newTarget) {
            return new ProxyConfig(
                enabled,
                autoTakeover,
                listen,
                newTarget,
                voiceChatPassthrough,
                voiceChatListen,
                voiceChatTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps,
                compression,
                voiceTransport,
                extraUdpPorts,
                transform,
                chunkCache
            );
        }

        private ProxyConfig withCompression(CompressionOptions newCompression) {
            return new ProxyConfig(
                enabled,
                autoTakeover,
                listen,
                target,
                voiceChatPassthrough,
                voiceChatListen,
                voiceChatTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps,
                newCompression,
                voiceTransport,
                extraUdpPorts,
                transform,
                chunkCache
            );
        }

        private ProxyConfig withEndpoints(HostPort newListen, HostPort newTarget) {
            return new ProxyConfig(
                enabled,
                autoTakeover,
                newListen,
                newTarget,
                voiceChatPassthrough,
                voiceChatListen,
                voiceChatTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps,
                compression,
                voiceTransport,
                extraUdpPorts,
                transform,
                chunkCache
            );
        }

        private ProxyConfig withLanVoiceDefaults(int lanPort) {
            String effectiveVoiceListen = voiceChatListen;
            String effectiveVoiceTarget = voiceChatTarget;
            if (isDefaultVoiceChatListen(voiceChatListen)) {
                effectiveVoiceListen = DEFAULT_LISTEN_HOST + ":" + lanPort;
            }
            if (isDefaultVoiceChatTarget(voiceChatTarget)) {
                effectiveVoiceTarget = "127.0.0.1:" + lanPort;
            }
            return new ProxyConfig(
                enabled,
                autoTakeover,
                listen,
                target,
                voiceChatPassthrough,
                effectiveVoiceListen,
                effectiveVoiceTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps,
                compression,
                voiceTransport,
                extraUdpPorts,
                transform,
                chunkCache
            );
        }

        private static boolean isDefaultVoiceChatListen(String raw) {
            return raw == null || raw.trim().isEmpty();
        }

        private static boolean isDefaultVoiceChatTarget(String raw) {
            return raw == null || raw.trim().isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ProxyConfig)) {
                return false;
            }
            ProxyConfig other = (ProxyConfig) o;
            return this.enabled == other.enabled
                && this.autoTakeover == other.autoTakeover
                && this.voiceChatPassthrough == other.voiceChatPassthrough
                && this.level == other.level
                && this.maxConnPerIp == other.maxConnPerIp
                && this.maxReqPerWindow == other.maxReqPerWindow
                && this.maxRatePerConnBps == other.maxRatePerConnBps
                && this.maxRateGlobalBps == other.maxRateGlobalBps
                && this.burstBytes == other.burstBytes
                && this.trustProxyProtocol == other.trustProxyProtocol
                && Objects.equals(this.listen, other.listen)
                && Objects.equals(this.target, other.target)
                && Objects.equals(this.voiceChatListen, other.voiceChatListen)
                && Objects.equals(this.voiceChatTarget, other.voiceChatTarget)
                && Objects.equals(this.window, other.window)
                && Objects.equals(this.banDuration, other.banDuration)
                && Objects.equals(this.statsInterval, other.statsInterval)
                && Objects.equals(this.flushInterval, other.flushInterval)
                && Objects.equals(this.idleTimeout, other.idleTimeout)
                && Objects.equals(this.trustedProxyIps, other.trustedProxyIps)
                && Objects.equals(this.compression, other.compression)
                && Objects.equals(this.voiceTransport, other.voiceTransport)
                && Objects.equals(this.extraUdpPorts, other.extraUdpPorts)
                && Objects.equals(this.transform, other.transform)
                && Objects.equals(this.chunkCache, other.chunkCache);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, autoTakeover, listen, target, voiceChatPassthrough, voiceChatListen,
                voiceChatTarget, level, maxConnPerIp, maxReqPerWindow, window, banDuration, statsInterval,
                flushInterval, idleTimeout, maxRatePerConnBps, maxRateGlobalBps, burstBytes, trustProxyProtocol,
                trustedProxyIps, compression, voiceTransport, extraUdpPorts, transform, chunkCache);
        }

        @Override
        public String toString() {
            return "ProxyConfig[enabled=" + enabled
                + ", autoTakeover=" + autoTakeover
                + ", listen=" + listen
                + ", target=" + target
                + ", voiceChatPassthrough=" + voiceChatPassthrough
                + ", voiceChatListen=" + voiceChatListen
                + ", voiceChatTarget=" + voiceChatTarget
                + ", level=" + level
                + ", maxConnPerIp=" + maxConnPerIp
                + ", maxReqPerWindow=" + maxReqPerWindow
                + ", window=" + window
                + ", banDuration=" + banDuration
                + ", statsInterval=" + statsInterval
                + ", flushInterval=" + flushInterval
                + ", idleTimeout=" + idleTimeout
                + ", maxRatePerConnBps=" + maxRatePerConnBps
                + ", maxRateGlobalBps=" + maxRateGlobalBps
                + ", burstBytes=" + burstBytes
                + ", trustProxyProtocol=" + trustProxyProtocol
                + ", trustedProxyIps=" + trustedProxyIps
                + ", compression=" + compression
                + ", voiceTransport=" + voiceTransport
                + ", extraUdpPorts=" + extraUdpPorts
                + ", transform=" + transform
                + ", chunkCache=" + chunkCache + "]";
        }

    }

    /**
     * host:port 解析模块（支持 IPv6/默认端口/去尾点）。
     */
    static final class UdpRoute {
        private final String label;
        private final HostPort listen;
        private final HostPort target;

        UdpRoute(String label, HostPort listen, HostPort target) {
            this.label = label;
            this.listen = listen;
            this.target = target;
        }

        public String label() { return this.label; }
        public HostPort listen() { return this.listen; }
        public HostPort target() { return this.target; }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof UdpRoute)) {
                return false;
            }
            UdpRoute other = (UdpRoute) o;
            return Objects.equals(this.label, other.label)
                && Objects.equals(this.listen, other.listen)
                && Objects.equals(this.target, other.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, listen, target);
        }

        @Override
        public String toString() {
            return "UdpRoute[label=" + label + ", listen=" + listen + ", target=" + target + "]";
        }
    }

    static final class VoiceChatPassthroughDecision {
        private final UdpRoute route;
        private final boolean reuseGameRoute;
        private final String reason;

        VoiceChatPassthroughDecision(UdpRoute route, boolean reuseGameRoute, String reason) {
            this.route = route;
            this.reuseGameRoute = reuseGameRoute;
            this.reason = reason;
        }

        public UdpRoute route() { return this.route; }
        public boolean reuseGameRoute() { return this.reuseGameRoute; }
        public String reason() { return this.reason; }

        static VoiceChatPassthroughDecision disabled(String reason) {
            return new VoiceChatPassthroughDecision(null, false, reason);
        }

        static VoiceChatPassthroughDecision reuseGameRoute(String reason) {
            return new VoiceChatPassthroughDecision(null, true, reason);
        }

        static VoiceChatPassthroughDecision route(UdpRoute route) {
            return new VoiceChatPassthroughDecision(route, false, null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof VoiceChatPassthroughDecision)) {
                return false;
            }
            VoiceChatPassthroughDecision other = (VoiceChatPassthroughDecision) o;
            return this.reuseGameRoute == other.reuseGameRoute
                && Objects.equals(this.route, other.route)
                && Objects.equals(this.reason, other.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(route, reuseGameRoute, reason);
        }

        @Override
        public String toString() {
            return "VoiceChatPassthroughDecision[route=" + route
                + ", reuseGameRoute=" + reuseGameRoute
                + ", reason=" + reason + "]";
        }
    }

    static final class HostPort {
        private final String host;
        private final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String host() { return this.host; }
        public int port() { return this.port; }

        static HostPort parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                throw new IllegalArgumentException("empty host:port");
            }
            String value = raw.trim();

            if (value.startsWith("[") && value.contains("]")) {
                int end = value.indexOf(']');
                String host = value.substring(1, end);
                int port = DEFAULT_MINECRAFT_PORT;
                if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                    port = Integer.parseInt(value.substring(end + 2).trim());
                }
                return new HostPort(normalizeHost(host), port);
            }

            int lastColon = value.lastIndexOf(':');
            int firstColon = value.indexOf(':');
            if (lastColon > 0 && firstColon == lastColon) {
                String host = value.substring(0, lastColon).trim();
                int port = Integer.parseInt(value.substring(lastColon + 1).trim());
                return new HostPort(normalizeHost(host), port);
            }
            return new HostPort(normalizeHost(value), DEFAULT_MINECRAFT_PORT);
        }

        InetSocketAddress toAddress() {
            return new InetSocketAddress(host, port);
        }

        private static String normalizeHost(String host) {
            String h = host.trim();
            if (h.endsWith(".") && h.length() > 1) {
                h = h.substring(0, h.length() - 1);
            }
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HostPort)) {
                return false;
            }
            HostPort other = (HostPort) o;
            return this.port == other.port && Objects.equals(this.host, other.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port);
        }

        @Override
        public String toString() {
            return "HostPort[host=" + host + ", port=" + port + "]";
        }
    }

    /**
     * 防刷模块：
     * - 限制每 IP 并发连接数
     * - 限制窗口内请求次数并封禁
     */
    private static final class FloodGuard {
        private final Map<String, GuardEntry> state = new ConcurrentHashMap<>();
        private final ProxyConfig cfg;

        private FloodGuard(ProxyConfig cfg) {
            this.cfg = cfg;
        }

        private synchronized boolean begin(String ip) {
            long now = System.currentTimeMillis();
            GuardEntry entry = state.computeIfAbsent(ip, k -> new GuardEntry());
            pruneRequests(entry, now);

            if (entry.bannedUntilMs > now) {
                return false;
            }

            if (cfg.maxReqPerWindow > 0 && !cfg.window.isZero() && !cfg.window.isNegative()) {
                entry.requestsMs.addLast(now);
                if (entry.requestsMs.size() > cfg.maxReqPerWindow) {
                    entry.bannedUntilMs = now + cfg.banDuration.toMillis();
                    return false;
                }
            }

            if (cfg.maxConnPerIp > 0 && entry.activeConn >= cfg.maxConnPerIp) {
                return false;
            }

            entry.activeConn++;
            return true;
        }

        private synchronized void end(String ip) {
            GuardEntry entry = state.get(ip);
            if (entry == null) {
                return;
            }
            if (entry.activeConn > 0) {
                entry.activeConn--;
            }
            long now = System.currentTimeMillis();
            pruneRequests(entry, now);
            if (isRemovable(entry, now)) {
                state.remove(ip);
            }
        }

        private synchronized void sweepExpired() {
            long now = System.currentTimeMillis();
            state.entrySet().removeIf(e -> {
                GuardEntry entry = e.getValue();
                pruneRequests(entry, now);
                return isRemovable(entry, now);
            });
        }

        private void pruneRequests(GuardEntry entry, long now) {
            if (cfg.window.isZero() || cfg.window.isNegative()) {
                entry.requestsMs.clear();
                return;
            }
            long cutoff = now - cfg.window.toMillis();
            while (!entry.requestsMs.isEmpty() && entry.requestsMs.peekFirst() < cutoff) {
                entry.requestsMs.removeFirst();
            }
        }

        private boolean isRemovable(GuardEntry entry, long now) {
            return entry.activeConn == 0 && entry.requestsMs.isEmpty() && entry.bannedUntilMs <= now;
        }

        private static final class GuardEntry {
            private int activeConn;
            private long bannedUntilMs;
            private final Deque<Long> requestsMs = new ArrayDeque<>();
        }
    }

    /**
     * 带限速控制的输出流模块。
     */
    private static final class RateLimitedOutputStream extends OutputStream {
        private static final int CHUNK_SIZE = 16 * 1024;

        private final OutputStream delegate;
        private final TokenBucketLimiter perConnLimiter;
        private final TokenBucketLimiter globalLimiter;

        private RateLimitedOutputStream(
            OutputStream delegate,
            TokenBucketLimiter perConnLimiter,
            TokenBucketLimiter globalLimiter
        ) {
            this.delegate = Objects.requireNonNull(delegate);
            this.perConnLimiter = perConnLimiter;
            this.globalLimiter = globalLimiter;
        }

        @Override
        public void write(int b) throws IOException {
            throttle(1);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int written = 0;
            while (written < len) {
                int chunk = Math.min(CHUNK_SIZE, len - written);
                throttle(chunk);
                delegate.write(b, off + written, chunk);
                written += chunk;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void throttle(int n) {
            if (perConnLimiter != null) {
                perConnLimiter.waitBytes(n);
            }
            if (globalLimiter != null) {
                globalLimiter.waitBytes(n);
            }
        }
    }

    private void startUdpForwarders(ProxyConfig config) {
        VoicePortPlan plan = computeVoicePortPlan(config);
        this.voicePortPlan = plan;

        // tunnel 模式：把探测到的语音端口作为「入口端口上的多路复用通道」挂到 game forwarder，
        // 语音 UDP 与游戏 UDP 共用同一个公网入口端口（见 VoiceTunnelFrame）。
        // bridge / off：game forwarder 不带语音通道，语音由客户端直连真实服务器同端口（或不处理）。
        List<HostPort> tunnelTargets = plan.isTunnel()
            ? plan.ports().stream().map(p -> new HostPort("127.0.0.1", p)).collect(Collectors.toList())
            : Collections.emptyList();

        List<UdpRoute> routes = buildUdpRoutes(config, plan);
        List<UdpForwarder> started = new ArrayList<>();
        for (UdpRoute route : routes) {
            List<HostPort> voiceChannels = "game".equals(route.label()) ? tunnelTargets : Collections.emptyList();
            try {
                UdpForwarder forwarder = new UdpForwarder(route, voiceChannels);
                forwarder.start();
                started.add(forwarder);
                if (voiceChannels.isEmpty()) {
                    LOGGER.info("[zstdnet-server] UDP route armed [{}]: {} -> {}", route.label(), route.listen(), route.target());
                } else {
                    LOGGER.info(
                        "[zstdnet-server] UDP route armed [{}]: {} -> {} (+{} voice tunnel channel(s) on the entry port: {})",
                        route.label(),
                        route.listen(),
                        route.target(),
                        voiceChannels.size(),
                        plan.ports()
                    );
                }
            } catch (Exception e) {
                LOGGER.warn("[zstdnet-server] UDP route skipped [{}] {} -> {}: {}", route.label(), route.listen(), route.target(), e.toString());
            }
        }
        this.udpForwarders = started;
    }

    /**
     * 探测后端语音 mod 的独立端口，结合 {@code voice_transport} 得出本次的语音端口计划。
     * 结果同时用于：(1) tunnel 模式下 game forwarder 的多路复用通道；(2) 通过网络包下发给客户端，
     * 让客户端在本机为这些端口开监听（隧道打标 / 直连桥接）。
     */
    private VoicePortPlan computeVoicePortPlan(ProxyConfig config) {
        if (!config.voiceChatPassthrough) {
            return VoicePortPlan.empty();
        }
        String transport = normalizeVoiceTransport(config.voiceTransport);
        List<VoicePortDetector.VoicePort> detected =
            VoicePortDetector.detect(Platforms.get().voiceConfigRoots(), config.target.port(), config.extraUdpPorts);
        List<Integer> ports = detected.stream().map(VoicePortDetector.VoicePort::port).collect(Collectors.toList());
        if (!ports.isEmpty()) {
            LOGGER.info("[zstdnet-server] voice ports detected (transport={}): {}", transport, ports);
        }
        return new VoicePortPlan(transport, ports);
    }

    private static String normalizeVoiceTransport(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "bridge".equals(v) ? "bridge" : "tunnel";
    }

    /** 当前生效的语音端口计划（供服务端引导层下发给客户端）。 */
    public VoicePortPlan currentVoicePortPlan() {
        return voicePortPlan;
    }

    private List<UdpRoute> buildUdpRoutes(ProxyConfig config, VoicePortPlan plan) {
        List<UdpRoute> routes = new ArrayList<>();
        routes.add(new UdpRoute("game", config.listen, config.target));

        VoiceChatPassthroughDecision voiceChat = resolveVoiceChatPassthrough(config);
        if (voiceChat.reuseGameRoute()) {
            LOGGER.info("[zstdnet-server] voice chat UDP passthrough reuses the built-in game UDP route.");
            return routes;
        }
        if (voiceChat.route() != null) {
            routes.add(voiceChat.route());
            return routes;
        }

        if (config.voiceChatPassthrough) {
            // 新的隧道/桥接计划（见 computeVoicePortPlan）已接管探测到的独立语音端口时，旧的单端口
            // passthrough 在独立端口场景下本就返回"未武装"——此时它只是被取代而非故障，降为 INFO，
            // 避免与上面 "voice ports detected / UDP route armed (+voice tunnel...)" 的 INFO 自相矛盾、
            // 误导服主去填已被取代的 voice_chat_listen。仅当新计划也没覆盖语音时才保留 WARN。
            if (plan != null && !plan.ports().isEmpty()) {
                LOGGER.info(
                    "[zstdnet-server] voice handled by the {} plan {}; legacy single-port passthrough not used ({}).",
                    plan.transport(),
                    plan.ports(),
                    voiceChat.reason()
                );
            } else {
                LOGGER.warn("[zstdnet-server] voice chat UDP passthrough not armed: {}", voiceChat.reason());
            }
        } else {
            LOGGER.info("[zstdnet-server] voice chat UDP passthrough disabled.");
        }
        return routes;
    }

    private void stopUdpForwarders() {
        List<UdpForwarder> forwarders = this.udpForwarders;
        this.udpForwarders = Collections.emptyList();
        for (UdpForwarder forwarder : forwarders) {
            try {
                forwarder.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class UdpForwarder {
        private static final int UDP_BUF_SIZE = 65535;
        private static final long SESSION_TIMEOUT_MS = 60_000L;

        private static final int CHANNEL_GAME = -1;

        private final UdpRoute route;
        // channelId -> 后端语音端口；为空表示本路由不承载语音隧道（仅裸游戏透传，行为同历史版本）。
        private final List<HostPort> voiceTargets;
        private volatile boolean running;
        private DatagramSocket serverSocket;
        private Thread forwardThread;
        private final Map<SessionKey, UdpSession> sessions = new ConcurrentHashMap<>();

        UdpForwarder(UdpRoute route, List<HostPort> voiceTargets) {
            this.route = route;
            this.voiceTargets = voiceTargets == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(voiceTargets));
        }

        void start() throws IOException {
            serverSocket = new DatagramSocket(null);
            serverSocket.setReuseAddress(true);
            serverSocket.bind(route.listen().toAddress());
            serverSocket.setSoTimeout(1000);
            running = true;

            forwardThread = new Thread(this::forwardLoop, "zstdsrv-udp-fwd-" + route.label());
            forwardThread.setDaemon(true);
            forwardThread.start();
        }

        void stop() {
            running = false;
            if (serverSocket != null) {
                serverSocket.close();
            }
            for (UdpSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
            if (forwardThread != null) {
                try {
                    forwardThread.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void forwardLoop() {
            byte[] buf = new byte[UDP_BUF_SIZE];
            InetSocketAddress gameTarget = route.target().toAddress();
            long lastSweep = System.currentTimeMillis();

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        serverSocket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        sweepIfNeeded(lastSweep);
                        lastSweep = System.currentTimeMillis();
                        continue;
                    }

                    SocketAddress clientAddr = packet.getSocketAddress();
                    int len = packet.getLength();
                    int off = packet.getOffset();
                    byte[] raw = packet.getData();

                    // demux：带 ZV1 头的是语音隧道帧，按 channelId 转对应后端语音端口；否则裸透传给后端游戏端口（向后兼容）。
                    int channel;
                    InetSocketAddress dest;
                    byte[] payload;
                    if (!voiceTargets.isEmpty() && VoiceTunnelFrame.isFrame(raw, off, len)) {
                        int cid = VoiceTunnelFrame.channelId(raw, off);
                        if (cid >= voiceTargets.size()) {
                            continue;
                        }
                        channel = cid;
                        dest = voiceTargets.get(cid).toAddress();
                        int payloadLen = len - VoiceTunnelFrame.HEADER_LEN;
                        payload = new byte[payloadLen];
                        System.arraycopy(raw, off + VoiceTunnelFrame.HEADER_LEN, payload, 0, payloadLen);
                    } else {
                        channel = CHANNEL_GAME;
                        dest = gameTarget;
                        payload = new byte[len];
                        System.arraycopy(raw, off, payload, 0, len);
                    }

                    SessionKey key = new SessionKey(clientAddr, channel);
                    InetSocketAddress sessionDest = dest;
                    UdpSession session = sessions.computeIfAbsent(key, k -> createSession(k, sessionDest));
                    if (session == null) {
                        continue;
                    }
                    session.lastActivity = System.currentTimeMillis();
                    session.socket.send(new DatagramPacket(payload, payload.length, dest));

                    long now = System.currentTimeMillis();
                    if (now - lastSweep > 10_000L) {
                        sweepIfNeeded(lastSweep);
                        lastSweep = now;
                    }
                } catch (IOException e) {
                    if (running) {
                        LOGGER.debug("[zstdnet-server] UDP forward error [{}]: {}", route.label(), e.toString());
                    }
                }
            }
        }

        private UdpSession createSession(SessionKey key, InetSocketAddress targetAddr) {
            try {
                DatagramSocket clientSocket = new DatagramSocket();
                clientSocket.setSoTimeout(1000);
                UdpSession session = new UdpSession(clientSocket, key);

                Thread returnThread = new Thread(
                    () -> returnLoop(session),
                    "zstdsrv-udp-ret-" + route.label() + "-ch" + key.channel() + "-" + key.clientAddr()
                );
                returnThread.setDaemon(true);
                returnThread.start();
                session.returnThread = returnThread;
                return session;
            } catch (IOException e) {
                LOGGER.debug("[zstdnet-server] UDP session create failed [{}] for {}: {}", route.label(), key.clientAddr(), e.toString());
                return null;
            }
        }

        private void returnLoop(UdpSession session) {
            byte[] buf = new byte[UDP_BUF_SIZE];
            int channel = session.key.channel();
            InetSocketAddress clientAddr = (InetSocketAddress) session.key.clientAddr();
            while (running && !session.socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        session.socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT_MS) {
                            break;
                        }
                        continue;
                    }
                    session.lastActivity = System.currentTimeMillis();

                    int len = packet.getLength();
                    int off = packet.getOffset();
                    byte[] out;
                    if (channel == CHANNEL_GAME) {
                        out = new byte[len];
                        System.arraycopy(packet.getData(), off, out, 0, len);
                    } else {
                        // 语音回程：重新打 ZV1 头，客户端据 channelId 投回对应本地语音端口。
                        out = VoiceTunnelFrame.wrap(channel, packet.getData(), off, len);
                    }
                    serverSocket.send(new DatagramPacket(out, out.length, clientAddr));
                } catch (IOException e) {
                    if (running && !session.socket.isClosed()) {
                        LOGGER.debug("[zstdnet-server] UDP return error [{}] for {}: {}", route.label(), session.key.clientAddr(), e.toString());
                    }
                    break;
                }
            }
            sessions.remove(session.key);
            session.close();
        }

        private void sweepIfNeeded(long lastSweep) {
            long now = System.currentTimeMillis();
            if (now - lastSweep < 10_000L) {
                return;
            }
            sessions.entrySet().removeIf(entry -> {
                UdpSession s = entry.getValue();
                if (now - s.lastActivity > SESSION_TIMEOUT_MS) {
                    s.close();
                    return true;
                }
                return false;
            });
        }

        private static final class SessionKey {
            private final SocketAddress clientAddr;
            private final int channel;

            private SessionKey(SocketAddress clientAddr, int channel) {
                this.clientAddr = clientAddr;
                this.channel = channel;
            }

            public SocketAddress clientAddr() { return this.clientAddr; }
            public int channel() { return this.channel; }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof SessionKey)) {
                    return false;
                }
                SessionKey other = (SessionKey) o;
                return this.channel == other.channel
                    && Objects.equals(this.clientAddr, other.clientAddr);
            }

            @Override
            public int hashCode() {
                return Objects.hash(clientAddr, channel);
            }

            @Override
            public String toString() {
                return "SessionKey[clientAddr=" + clientAddr + ", channel=" + channel + "]";
            }
        }

        private static final class UdpSession {
            final DatagramSocket socket;
            final SessionKey key;
            volatile long lastActivity;
            volatile Thread returnThread;

            UdpSession(DatagramSocket socket, SessionKey key) {
                this.socket = socket;
                this.key = key;
                this.lastActivity = System.currentTimeMillis();
            }

            void close() {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 线程命名工厂模块，便于日志与诊断定位线程用途。
     */
    private static final class NamedFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger index = new AtomicInteger(1);

        private NamedFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + index.getAndIncrement());
            t.setDaemon(true);
            // 后台压缩/转发/统计线程降低优先级，CPU 紧张时让位给服务器主线程（不改变压缩 level）。
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return t;
        }
    }
}
