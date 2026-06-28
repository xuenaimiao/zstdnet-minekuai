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

package cn.tohsaka.factory.zstdnet.core.cache;

import cn.tohsaka.factory.zstdnet.core.cache.patch.ChunkStructureParser;
import cn.tohsaka.factory.zstdnet.core.cache.patch.DeltaCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import cn.tohsaka.factory.zstdnet.core.transform.PacketFramer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CRC 编码输出流（服务端）：位于代理下行写循环与 ZSTD 压缩器之间。调用方把后端原始字节写进来，本流用
 * {@link PacketFramer} 切出完整 MC 帧，在 {@link #flush()} 时逐帧决策：
 * <ul>
 *   <li>可缓存包（{@link CacheablePacketTable}）且客户端已持有相同字节 → 发 {@link ChunkCacheFormat#BLOCK_REF}（8 字节令牌）；</li>
 *   <li>可缓存包但客户端没有 → 发 {@link ChunkCacheFormat#BLOCK_FULL} 并入缓存；</li>
 *   <li>其余帧 → 合并成 {@link ChunkCacheFormat#BLOCK_PASSTHROUGH} 逐字节透传。</li>
 * </ul>
 * 流首一次性写 PREAMBLE（对端 {@link CacheUntransformingInputStream} 据此识别）。关闭时落定剩余完整帧 + 把
 * 不足一帧的尾字节也当 PASSTHROUGH 原样写出，保证逆向逐字节还原。
 *
 * <p>正确性见 {@link ChunkCacheFormat} 与 {@link LruByteCache}：REF 仅在 {@link LruByteCache#peek} 命中且整字节相等时
 * 发出（规避哈希碰撞），编码端缓存与客户端缓存同步淘汰 → 会话内 REF 不会 miss。
 */
public final class CacheTransformingOutputStream extends OutputStream {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-chunk-cache");

    private final OutputStream out;
    private final PacketFramer framer = new PacketFramer();
    private final CacheablePacketTable table;
    private final LruByteCache cache;
    /** 客户端经 manifest 声明持有的跨会话 hash128 集合（空 = 无跨会话）。仅当 {@code version>=2} 时启用 WARM_REF。 */
    private final Set<Hash128> clientWarm;
    /** 协商出的生效 CRC 版本（写入 PREAMBLE；{@code >=2} 才发 WARM_REF，{@code >=3} 才发 PATCH）。 */
    private final int version;
    /** 自适应旁路窗口（0 = 关闭）。见 {@link ChunkCacheFormat#DEFAULT_AUTO_BYPASS_WINDOW}。 */
    private final int bypassWindow;
    /** 是否启用结构无关字节级 PATCH（需 {@code version>=3} 且服务端 mode∈{FULL,AUTO}）。 */
    private final boolean patchEnabled;
    /** 可选的编码端结构感知增量解析器（当前阶段恒 {@code null} → 通用字节 diff）；只影响增量大小、不影响正确性。 */
    private final ChunkStructureParser structureParser;
    private final int patchBudget = ChunkCacheFormat.DEFAULT_PATCH_CHAIN_BUDGET;
    private final int patchMaxRatioPercent = ChunkCacheFormat.DEFAULT_PATCH_MAX_RATIO_PERCENT;
    private final byte[] one = new byte[1];
    private final long[] posKey = new long[1]; // readChunkPosKey 复用，免每帧分配
    private boolean preambleWritten = false;
    private boolean closed = false;

    /**
     * 每个区块坐标的 PATCH 基线状态（编码端独有；解码端不需要——PATCH block 显式带 baseHash）。
     * {@code lastHi} = 该坐标最近一次发出的、且仍在会话内 LRU 的帧 hash（可作下次 PATCH 基线）；
     * {@code chain} = 自上次 FULL 重锚以来的连续 PATCH 数（达预算强制 FULL）。有界 LRU 淘汰，失一条只少一次 PATCH。
     */
    private static final class PosState {
        long lastHi;
        int chain;

        PosState(long lastHi) {
            this.lastHi = lastHi;
        }
    }

    private static final int MAX_TRACKED_POSITIONS = 1 << 16;
    private final Map<Long, PosState> positionMap = new LinkedHashMap<Long, PosState>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PosState> eldest) {
            return size() > MAX_TRACKED_POSITIONS;
        }
    };

    // 自适应旁路状态（纯编码端、对解码端零影响）。
    private int newRun = 0;          // 连续“全新”帧计数（未命中 REF/WARM）
    private boolean bypassing = false;
    private int bypassCountdown = 0; // 旁路剩余帧数
    private boolean probing = false; // 当前帧是旁路期满后的一次探针 FULL

    // 统计（仅用于日志/HUD，非正确性必需）。
    long refBlocks = 0L;
    long refSavedBytes = 0L; // 被 REF 替掉的原始帧总字节（= 节省量的近似）
    long warmRefBlocks = 0L;
    long warmRefSavedBytes = 0L;
    long patchBlocks = 0L;
    long patchSavedBytes = 0L; // 相对“整发 FULL”省下的字节（近似）
    long fullBlocks = 0L;
    long passthroughBytes = 0L;
    long bypassedFrames = 0L;

    /**
     * 仅会话内缓存（无跨会话 WARM_REF、无旁路）。供单测与 v1 协商使用。
     *
     * @param out      底层流（通常即 ZSTD 压缩器）。
     * @param table    编码端可缓存包表（按 MC 协议版本解析；{@code null} → 全 passthrough，仍字节精确）。
     * @param maxBytes 缓存字节预算（须与客户端一致，以保证同步淘汰）。
     */
    public CacheTransformingOutputStream(OutputStream out, CacheablePacketTable table, long maxBytes) {
        this(out, table, maxBytes, Collections.emptySet(), ChunkCacheFormat.VERSION_REF, 0, false, 0);
    }

    /**
     * 会话内缓存 + 跨会话 WARM_REF（v2）+ 自适应旁路（无 PATCH）。
     */
    public CacheTransformingOutputStream(OutputStream out, CacheablePacketTable table, long maxBytes,
                                         Set<Hash128> clientWarm, int version, int bypassWindow) {
        this(out, table, maxBytes, clientWarm, version, bypassWindow, false, 0);
    }

    /**
     * 会话内缓存 + 跨会话 WARM_REF（v2）+ 自适应旁路 + 结构无关 PATCH（v3）。
     *
     * @param clientWarm      客户端 manifest 声明持有的 hash128 集合（{@code null} 视为空）；仅 {@code version>=2} 时用。
     * @param version         协商出的生效 CRC 版本（写入 PREAMBLE）。
     * @param bypassWindow    自适应旁路窗口（0 = 关闭）。
     * @param patchEnabled    是否启用 PATCH（需 {@code version>=3}，由服务端 mode 决定）。
     * @param protocolVersion MC 协议版本（仅用于挑选可选的结构感知增量解析器；当前阶段无内置实现）。
     */
    public CacheTransformingOutputStream(OutputStream out, CacheablePacketTable table, long maxBytes,
                                         Set<Hash128> clientWarm, int version, int bypassWindow,
                                         boolean patchEnabled, int protocolVersion) {
        this.out = out;
        this.table = table;
        this.cache = new LruByteCache(maxBytes);
        this.clientWarm = clientWarm == null ? Collections.emptySet() : clientWarm;
        this.version = version;
        this.bypassWindow = Math.max(0, bypassWindow);
        this.patchEnabled = patchEnabled && version >= ChunkCacheFormat.VERSION_PATCH;
        this.structureParser = this.patchEnabled ? ChunkStructureParser.forProtocol(protocolVersion) : null;
    }

    @Override
    public void write(int b) throws IOException {
        one[0] = (byte) b;
        write(one, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("zstdnet chunk-cache: write after close");
        }
        framer.append(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        PacketFramer.Batch batch = framer.peelComplete();
        if (batch != null) {
            ensurePreamble();
            encodeBatch(batch);
            framer.consume(batch);
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (OutputStream underlying = out) {
            PacketFramer.Batch batch = framer.peelComplete();
            if (batch != null) {
                ensurePreamble();
                encodeBatch(batch);
                framer.consume(batch);
            }
            if (framer.hasRemainder()) {
                // 流尾不足一帧的剩余字节：当 PASSTHROUGH 原样写出（解码端逐字节还原）。
                ensurePreamble();
                byte[] tail = framer.drainRemainder();
                ChunkCacheCodec.writePassthrough(underlying, tail, 0, tail.length);
                passthroughBytes += tail.length;
            }
            underlying.flush();
        }
        if ((refBlocks > 0 || warmRefBlocks > 0 || patchBlocks > 0) && LOGGER.isInfoEnabled()) {
            double savedMiB = (refSavedBytes + warmRefSavedBytes + patchSavedBytes) / (1024.0 * 1024.0);
            LOGGER.info("[chunk_cache] connection closed: in-session REF={}, cross-session WARM_REF={}, PATCH={} (~{} MiB chunk bytes saved by tokens/deltas), FULL sent={}, bypassed={}",
                refBlocks, warmRefBlocks, patchBlocks, String.format("%.2f", savedMiB), fullBlocks, bypassedFrames);
        }
    }

    /** 逐帧编码一个 batch：合并连续不可缓存帧为 PASSTHROUGH，可缓存帧发 FULL/REF。 */
    private void encodeBatch(PacketFramer.Batch batch) throws IOException {
        byte[] buf = batch.buffer;
        int pos = batch.start;
        int passStart = -1; // 待写 PASSTHROUGH 段的起点（-1 表示无）
        int passEnd = -1;
        while (pos < batch.end) {
            VarIntRead lenPrefix = VarIntCodec.read(buf, pos, batch.end);
            if (lenPrefix == null) {
                break; // peelComplete 已保证完整，理论不达
            }
            int payloadStart = lenPrefix.next();
            int frameEnd = payloadStart + lenPrefix.value();
            if (frameEnd > batch.end) {
                break;
            }
            if (ChunkCacheCodec.isCacheableFrame(buf, payloadStart, frameEnd, table)) {
                flushPassthrough(buf, passStart, passEnd);
                passStart = -1;
                encodeCacheableFrame(buf, pos, payloadStart, frameEnd);
            } else {
                if (passStart < 0) {
                    passStart = pos;
                }
                passEnd = frameEnd;
            }
            pos = frameEnd;
        }
        flushPassthrough(buf, passStart, passEnd);
    }

    private void flushPassthrough(byte[] buf, int start, int end) throws IOException {
        if (start < 0) {
            return;
        }
        ChunkCacheCodec.writePassthrough(out, buf, start, end - start);
        passthroughBytes += end - start;
    }

    private void encodeCacheableFrame(byte[] buf, int frameStart, int payloadStart, int frameEnd) throws IOException {
        int frameLen = frameEnd - frameStart;
        // 只算一次 128 位哈希：hi 半即会话内 8 字节令牌（== content64），整体即跨会话 16 字节键。
        Hash128 h128 = Hashing.content128(buf, frameStart, frameLen);
        long hi = h128.hi();

        // 区块坐标（仅全区块包能解出；用于 PATCH 选基线 + 维护 positionMap）。
        boolean hasPos = patchEnabled && ChunkCacheCodec.readChunkPosKey(buf, payloadStart, frameEnd, posKey);
        long pos = hasPos ? posKey[0] : 0L;

        // (1) 会话内 REF：peek 命中 + 整字节比对（规避碰撞）→ 8 字节令牌。
        byte[] stored = cache.peek(hi);
        if (stored != null
            && stored.length == frameLen
            && regionEquals(stored, 0, stored.length, buf, frameStart, frameEnd)) {
            cache.get(hi); // 与客户端 REF 解码的 get 同步触碰
            ChunkCacheCodec.writeRef(out, hi);
            refBlocks++;
            refSavedBytes += frameLen;
            if (hasPos) {
                anchor(pos, hi); // 精确帧仍在 LRU，是干净基线 → 重锚、清链
            }
            onCacheHit();
            return;
        }

        // (2) 跨会话 WARM_REF：客户端 manifest 声明持有 → 16 字节令牌。128 位抗碰撞，无需也无法字节比对。
        //     不动会话内 LRU（客户端 WARM_REF 也不动）→ lock-step 不受影响。WARM 帧不进 LRU，故不可作 PATCH 基线、
        //     也不更新 positionMap（保留旧的、仍在 LRU 的可用基线）。
        if (version >= ChunkCacheFormat.VERSION_MANIFEST && clientWarm.contains(h128)) {
            ChunkCacheCodec.writeWarmRef(out, h128);
            warmRefBlocks++;
            warmRefSavedBytes += frameLen;
            onCacheHit();
            return;
        }

        byte[] frame = null; // 惰性拷贝：仅在确实要 PATCH/FULL 时分配（旁路 PASSTHROUGH 不需要）

        // (3) PATCH：相对该坐标上次仍在 LRU 的帧发字节级增量（划算才发）。基线用 peek（不触碰 LRU）→ lock-step。
        if (patchEnabled && hasPos) {
            PosState ps = positionMap.get(pos);
            if (ps != null && ps.chain < patchBudget) {
                byte[] base = cache.peek(ps.lastHi);
                if (base != null) {
                    frame = Arrays.copyOfRange(buf, frameStart, frameEnd);
                    byte[] delta = structureParser != null ? structureParser.structuralDiff(base, frame) : null;
                    if (delta == null) {
                        delta = DeltaCodec.diff(base, frame);
                    }
                    if ((long) delta.length * 100 <= (long) frameLen * patchMaxRatioPercent) {
                        ChunkCacheCodec.writePatch(out, ps.lastHi, hi, delta);
                        cache.put(hi, frame); // 重建结果进 LRU（解码端亦 put）→ lock-step
                        ps.lastHi = hi;
                        ps.chain++;
                        patchBlocks++;
                        patchSavedBytes += Math.max(0, frameLen - patchWireLen(delta.length));
                        onCacheHit();
                        return;
                    }
                }
            }
        }

        // (4) 全新帧：自适应旁路决定 FULL（入缓存）还是 PASSTHROUGH（不缓存，省 RAM churn）。
        if (bypassWindow > 0 && bypassing) {
            if (bypassCountdown > 0) {
                bypassCountdown--;
                ChunkCacheCodec.writePassthrough(out, buf, frameStart, frameLen);
                passthroughBytes += frameLen;
                bypassedFrames++;
                return;
            }
            // 旁路期满 → 本帧作为一次探针 FULL（若仍未命中，下方立即回旁路）。
            bypassing = false;
            probing = true;
        }
        if (frame == null) {
            frame = Arrays.copyOfRange(buf, frameStart, frameEnd);
        }
        cache.put(hi, frame);
        ChunkCacheCodec.writeFull(out, hi, frame);
        fullBlocks++;
        if (hasPos) {
            anchor(pos, hi); // FULL 是干净基线 → 重锚、清链
        }
        if (bypassWindow > 0) {
            if (probing) {
                probing = false;
                bypassing = true;
                bypassCountdown = bypassWindow; // 探针未命中 → 立刻回旁路（每窗口仅 1 次探针 FULL）
            } else {
                newRun++;
                if (newRun >= bypassWindow) {
                    bypassing = true;
                    bypassCountdown = bypassWindow;
                    newRun = 0;
                }
            }
        }
    }

    /** 把某区块坐标的 PATCH 基线重锚到 {@code hi}（一个仍在会话内 LRU 的干净帧），并清零 patch 链计数。 */
    private void anchor(long pos, long hi) {
        PosState ps = positionMap.get(pos);
        if (ps == null) {
            positionMap.put(pos, new PosState(hi));
        } else {
            ps.lastHi = hi;
            ps.chain = 0;
        }
    }

    /** 比较 {@code a[aOff, aEnd)} 与 {@code b[bOff, bEnd)} 两个区间是否逐字节相等（替代 JDK9 的 {@code Arrays.equals} 六参版）。 */
    private static boolean regionEquals(byte[] a, int aOff, int aEnd, byte[] b, int bOff, int bEnd) {
        if (aEnd - aOff != bEnd - bOff) {
            return false;
        }
        for (int i = 0, n = aEnd - aOff; i < n; i++) {
            if (a[aOff + i] != b[bOff + i]) {
                return false;
            }
        }
        return true;
    }

    /** PATCH block 的线上字节数（用于估算相对 FULL 省下多少）：type(1)+base(8)+new(8)+varint(deltaLen)+delta。 */
    private static int patchWireLen(int deltaLen) {
        return 1 + ChunkCacheFormat.HASH_BYTES * 2 + VarIntCodec.encode(deltaLen).length + deltaLen;
    }

    /** 任一 REF/WARM/PATCH 命中：说明缓存正在生效 → 退出旁路、清零计数。 */
    private void onCacheHit() {
        newRun = 0;
        bypassing = false;
        bypassCountdown = 0;
        probing = false;
    }

    private void ensurePreamble() throws IOException {
        if (preambleWritten) {
            return;
        }
        preambleWritten = true;
        ChunkCacheCodec.writePreamble(out, version);
    }
}
