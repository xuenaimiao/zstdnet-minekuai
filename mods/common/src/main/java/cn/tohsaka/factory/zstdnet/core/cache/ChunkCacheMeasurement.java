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

package cn.tohsaka.factory.zstdnet.core.cache;

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import cn.tohsaka.factory.zstdnet.core.transform.PacketFramer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code chunk_cache=measure} 的只读埋点（CRC 区块引用缓存的决策闸门）。<b>不改任何字节</b>——只在
 * “服务端→客户端”转发循环里旁路 tap 一份原始字节，统计“引用缓存能去掉多少重复流量”，并区分其中
 * <b>现有 ZSTD/LDM 窗口已能折叠</b>的部分与<b>窗口外 / 跨会话只有显式缓存才能拿到</b>的边际部分。
 *
 * <p>三类信号：
 * <ul>
 *   <li><b>会话内重复（within-window / beyond-window）</b>：同一连接里再次出现的相同大帧。距上次出现
 *       ≤ 假定匹配窗口的，ZSTD/LDM 多半已折叠（边际收益低）；&gt; 窗口的，连续帧匹配够不到，是显式缓存的净收益。</li>
 *   <li><b>跨会话重复</b>：本连接首见、但在<b>之前的连接</b>里出现过的相同大帧（共享指纹库判定）。每次重连
 *       ZSTD 状态清零，故这部分 LDM 永远够不到——是持久化客户端缓存的纯净收益。</li>
 *   <li><b>按 id 全区块包</b>：用 {@link CacheablePacketTable} 把上面两类里专属于全区块包的部分单列出来，
 *       与“按大小”总量交叉校验（详见 {@link CacheablePacketTable} 注释）。</li>
 * </ul>
 *
 * <p>埋点 best-effort：分帧异常只停掉本连接的测量、绝不影响转发；内存有上限（每连接 + 全局指纹库均封顶）。
 */
public final class ChunkCacheMeasurement {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-chunk-cache");

    /** 小于此值的帧不参与去重统计（区块 / 方块实体等目标包远大于此；小包不值得缓存，且省内存）。 */
    private static final int MIN_TRACK_BYTES = 256;
    /** 每连接指纹→偏移表的条目上限（内存保护）。 */
    private static final int MAX_TRACKED_PER_CONN = 1 << 18; // 262144
    /** 全局跨会话指纹库条目上限（内存保护）。 */
    private static final int MAX_GLOBAL_FINGERPRINTS = 1 << 20; // 1048576

    private final long windowBytes;
    private final boolean ldmAssumed;

    /** 跨会话指纹库：所有连接共享，判定“本帧是否在之前某条连接出现过”。 */
    private final ConcurrentHashMap<Long, Boolean> globallySeen = new ConcurrentHashMap<>();

    private final AtomicLong connectionCount = new AtomicLong();
    private final AtomicLong gTotalBytes = new AtomicLong();
    private final AtomicLong gLargeBytes = new AtomicLong();
    private final AtomicLong gInSessionRepeatBytes = new AtomicLong();
    private final AtomicLong gWithinWindowBytes = new AtomicLong();
    private final AtomicLong gBeyondWindowBytes = new AtomicLong();
    private final AtomicLong gCrossSessionBytes = new AtomicLong();
    private final AtomicLong gFullChunkBytes = new AtomicLong();
    private final AtomicLong gFullChunkRepeatBytes = new AtomicLong();

    public ChunkCacheMeasurement(CompressionOptions compression) {
        this(assumedWindowBytes(compression), compression != null && compression.longDistanceMatching());
    }

    /** 测试 / 内部用：显式指定假定匹配窗口（字节）。 */
    ChunkCacheMeasurement(long windowBytes, boolean ldmAssumed) {
        this.windowBytes = windowBytes;
        this.ldmAssumed = ldmAssumed;
        LOGGER.info(
            "[chunk_cache/measure] enabled; assumed ZSTD match window={} MiB (LDM={}). This mode changes NO bytes; it only logs repeat-traffic opportunity.",
            String.format("%.0f", windowBytes / (1024.0 * 1024.0)), ldmAssumed);
    }

    /** 假定的“ZSTD 能匹配到”的窗口：开 LDM 用其有效 windowLog；否则按 level 9 默认窗口（2^21≈2MiB）粗估。 */
    private static long assumedWindowBytes(CompressionOptions compression) {
        boolean ldm = compression != null && compression.longDistanceMatching();
        return ldm ? (1L << compression.effectiveWindowLog()) : (1L << 21);
    }

    /** 为一条连接开一个测量器。 */
    public Collector newCollector(int protocolVersion) {
        return new Collector(this, CacheablePacketTable.forProtocol(protocolVersion), protocolVersion);
    }

    /** 原子地“查看并登记”一个全局指纹；返回它在本次之前是否已被某条连接见过。 */
    private boolean markGlobalAndWasSeen(long hash) {
        if (globallySeen.containsKey(hash)) {
            return true;
        }
        if (globallySeen.size() < MAX_GLOBAL_FINGERPRINTS) {
            globallySeen.putIfAbsent(hash, Boolean.TRUE);
        }
        return false;
    }

    private void fold(Collector c) {
        long conns = connectionCount.incrementAndGet();
        gTotalBytes.addAndGet(c.totalBytes);
        gLargeBytes.addAndGet(c.largeBytes);
        gInSessionRepeatBytes.addAndGet(c.inSessionRepeatBytes);
        gWithinWindowBytes.addAndGet(c.repeatWithinWindowBytes);
        gBeyondWindowBytes.addAndGet(c.repeatBeyondWindowBytes);
        gCrossSessionBytes.addAndGet(c.crossSessionRepeatBytes);
        gFullChunkBytes.addAndGet(c.cacheableBytes);
        gFullChunkRepeatBytes.addAndGet(c.cacheableRepeatBytes);

        if (c.largeBytes <= 0) {
            return; // 没有可统计的大帧（多为极短连接 / 状态探测），不刷屏
        }
        LOGGER.info(
            "[chunk_cache/measure] conn proto={} down={} frames={} large={} | in-session repeat={} (within-window {} / beyond {}) | cross-session repeat={} | full-chunk(byId)={} repeat={}",
            c.protocolVersion,
            mib(c.totalBytes), c.totalFrames, mib(c.largeBytes),
            mib(c.inSessionRepeatBytes), mib(c.repeatWithinWindowBytes), mib(c.repeatBeyondWindowBytes),
            mib(c.crossSessionRepeatBytes),
            mib(c.cacheableBytes), mib(c.cacheableRepeatBytes));
        LOGGER.info(
            "[chunk_cache/measure] cumulative conns={} down={} | marginal-over-LDM: beyond-window={} + cross-session={} | full-chunk repeat={}",
            conns,
            mib(gTotalBytes.get()),
            mib(gBeyondWindowBytes.get()), mib(gCrossSessionBytes.get()),
            mib(gFullChunkRepeatBytes.get()));
    }

    private static String mib(long bytes) {
        return String.format("%.2fMiB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 单连接测量缓冲（非线程安全：仅由该连接的下行转发线程使用）。把原始字节增量分帧后逐帧统计。
     */
    public static final class Collector {
        private final ChunkCacheMeasurement parent;
        private final CacheablePacketTable table; // 可能为 null（未覆盖的协议版本）
        final int protocolVersion;

        private final PacketFramer framer = new PacketFramer();
        private final Map<Long, Long> lastOffsetByHash = new HashMap<>();
        private boolean broken = false;

        private long processedBytes = 0L; // 已处理的下行流偏移（= 各帧起点）

        long totalFrames = 0L;
        long totalBytes = 0L;
        long largeBytes = 0L;
        long inSessionRepeatBytes = 0L;
        long repeatWithinWindowBytes = 0L;
        long repeatBeyondWindowBytes = 0L;
        long crossSessionRepeatBytes = 0L;
        long cacheableBytes = 0L;
        long cacheableRepeatBytes = 0L;

        Collector(ChunkCacheMeasurement parent, CacheablePacketTable table, int protocolVersion) {
            this.parent = parent;
            this.table = table;
            this.protocolVersion = protocolVersion;
        }

        /** 旁路 tap 一段下行原始字节。绝不抛异常——分帧出错只停掉本连接的测量。 */
        public void accept(byte[] data, int off, int len) {
            if (broken || len <= 0) {
                return;
            }
            try {
                framer.append(data, off, len);
                PacketFramer.Batch batch;
                while ((batch = framer.peelComplete()) != null) {
                    walk(batch);
                    framer.consume(batch);
                }
            } catch (IOException | RuntimeException ex) {
                broken = true; // 分帧错位 / 损坏：放弃本连接测量，绝不拖累转发
            }
        }

        private void walk(PacketFramer.Batch batch) {
            byte[] buf = batch.buffer;
            int pos = batch.start;
            while (pos < batch.end) {
                VarIntRead lenPrefix = VarIntCodec.read(buf, pos, batch.end);
                if (lenPrefix == null) {
                    break; // 理论不达（peelComplete 已保证完整），保险
                }
                int payloadStart = lenPrefix.next();
                int frameEnd = payloadStart + lenPrefix.value();
                if (frameEnd > batch.end) {
                    break;
                }
                process(buf, pos, payloadStart, frameEnd);
                pos = frameEnd;
            }
        }

        private void process(byte[] buf, int frameStart, int payloadStart, int frameEnd) {
            int frameBytes = frameEnd - frameStart;
            long offset = processedBytes;
            processedBytes += frameBytes;
            totalFrames++;
            totalBytes += frameBytes;
            if (frameBytes < MIN_TRACK_BYTES) {
                return;
            }
            largeBytes += frameBytes;

            boolean cacheableId = false;
            if (table != null) {
                VarIntRead dl = VarIntCodec.read(buf, payloadStart, frameEnd);
                if (dl != null && dl.value() == 0) { // play 阶段未压缩内层包
                    VarIntRead pid = VarIntCodec.read(buf, dl.next(), frameEnd);
                    if (pid != null && table.isCacheable(pid.value())) {
                        cacheableId = true;
                    }
                }
            }
            if (cacheableId) {
                cacheableBytes += frameBytes;
            }

            long hash = Hashing.content64(buf, frameStart, frameBytes);
            boolean repeat;
            Long prev = lastOffsetByHash.get(hash);
            if (prev != null) {
                repeat = true;
                inSessionRepeatBytes += frameBytes;
                long gap = offset - prev;
                if (gap <= parent.windowBytes) {
                    repeatWithinWindowBytes += frameBytes;
                } else {
                    repeatBeyondWindowBytes += frameBytes;
                }
                lastOffsetByHash.put(hash, offset);
            } else {
                boolean wasGlobal = parent.markGlobalAndWasSeen(hash);
                repeat = wasGlobal;
                if (wasGlobal) {
                    crossSessionRepeatBytes += frameBytes;
                }
                if (lastOffsetByHash.size() < MAX_TRACKED_PER_CONN) {
                    lastOffsetByHash.put(hash, offset);
                }
            }
            if (cacheableId && repeat) {
                cacheableRepeatBytes += frameBytes;
            }
        }

        /** 连接结束时调用：汇总到全局并打印本连接 + 累计摘要。 */
        public void finish() {
            parent.fold(this);
        }
    }
}
