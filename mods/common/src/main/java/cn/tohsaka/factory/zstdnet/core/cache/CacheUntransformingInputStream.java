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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * CRC 逆变换输入流（客户端）：位于 ZSTD 解压器与游戏客户端之间。从解压流读出 CRC block 并还原成原始 MC 帧字节，
 * 与原始流逐字节一致。FULL 入缓存、REF 重放缓存、PASSTHROUGH 原样透传，能处理任意读边界（按显式长度等待）。
 *
 * <p><b>MAGIC 自检 / passthrough：</b>流首若匹配 {@link ChunkCacheFormat#PREAMBLE_MAGIC} 则进入 CRC 模式；
 * 否则透明原样透传——因此服务端未启用 / 改走实体变换（ZNTX）/ 未升级时，本包装仍能正常工作。
 * 故可与实体逆变换 {@code UntransformingInputStream} 自由嵌套：每层各认自己的 MAGIC、不认则透传，只会有一种生效。
 *
 * <p><b>fail-closed：</b>进入 CRC 模式后遇非法块 / 损坏 / REF 缺基线 / EOF 截断，抛 {@link IOException} 中止连接，
 * 绝不把错位字节喂给游戏。
 */
public final class CacheUntransformingInputStream extends InputStream {
    private static final int DETECTING = 0;
    private static final int TRANSFORM = 1;
    private static final int PASSTHROUGH = 2;

    private static final int READ_CHUNK = 16 * 1024;

    private final InputStream in;
    private final LruByteCache cache;
    /** 跨会话 warm 快照（hash128 → 帧字节），WARM_REF 从这里重放；空表表示本连接无持久缓存。 */
    private final Map<Hash128, byte[]> warm;
    /** 跨会话持久层（可空）：每个 FULL 帧写穿透落盘，供下次会话 WARM_REF。 */
    private final ChunkCacheStore store;
    /** 命中计数（可空）：每解出一个 block 旁路登记，供 HUD/指令展示；不影响解码正确性。 */
    private final CacheStats cacheStats;

    private byte[] accum = new byte[8 * 1024];
    private int aStart = 0;
    private int aEnd = 0;

    private final ByteArrayOutputStream decodeSink = new ByteArrayOutputStream();
    private byte[] out = new byte[0];
    private int oStart = 0;
    private int oEnd = 0;

    private int state = DETECTING;
    private boolean upstreamEof = false;
    @SuppressWarnings("unused")
    private int version = 0;

    /**
     * 仅会话内缓存（无跨会话持久化）。
     *
     * @param in       上游（通常即 ZSTD 解压流）。
     * @param maxBytes 客户端会话内缓存字节预算（须与服务端一致，以保证同步淘汰 → REF 不 miss）。
     */
    public CacheUntransformingInputStream(InputStream in, long maxBytes) {
        this(in, maxBytes, Collections.emptyMap(), null, null);
    }

    /** 会话内缓存 + 跨会话持久化（v2 WARM_REF），无命中计数。 */
    public CacheUntransformingInputStream(InputStream in, long maxBytes, Map<Hash128, byte[]> warm, ChunkCacheStore store) {
        this(in, maxBytes, warm, store, null);
    }

    /**
     * 会话内缓存 + 跨会话持久化（v2 WARM_REF / v3 PATCH）+ 命中计数。
     *
     * @param warm       本会话的 warm 快照（hash128 → 帧字节），与发给服务端的 manifest 同一来源；{@code null} 视为空。
     * @param store      跨会话持久层（可空）：每个 FULL/PATCH 帧写穿透落盘，供下次会话 WARM_REF。
     * @param cacheStats 命中计数（可空）：仅用于 HUD/指令展示。
     */
    public CacheUntransformingInputStream(InputStream in, long maxBytes, Map<Hash128, byte[]> warm,
                                          ChunkCacheStore store, CacheStats cacheStats) {
        this.in = in;
        this.cache = new LruByteCache(maxBytes);
        this.warm = warm == null ? Collections.emptyMap() : warm;
        this.store = store;
        this.cacheStats = cacheStats;
    }

    @Override
    public int read() throws IOException {
        if (!ensureOutput()) {
            return -1;
        }
        return out[oStart++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!ensureOutput()) {
            return -1;
        }
        int n = Math.min(len, oEnd - oStart);
        System.arraycopy(out, oStart, b, off, n);
        oStart += n;
        return n;
    }

    @Override
    public int available() {
        return oEnd - oStart;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private boolean ensureOutput() throws IOException {
        while (oStart >= oEnd) {
            if (!step()) {
                return false;
            }
        }
        return true;
    }

    private boolean step() throws IOException {
        switch (state) {
            case PASSTHROUGH:
                if (aStart < aEnd) {
                    serveAccum();
                    return true;
                }
                return readUpstream();
            case DETECTING:
                return stepDetect();
            case TRANSFORM:
                return stepTransform();
            default:
                throw new IllegalStateException("zstdnet chunk-cache: bad state " + state);
        }
    }

    private boolean stepDetect() throws IOException {
        if (aEnd - aStart >= ChunkCacheFormat.PREAMBLE_LENGTH) {
            if (preambleMatches()) {
                version = accum[aStart + ChunkCacheFormat.PREAMBLE_MAGIC.length] & 0xFF;
                aStart += ChunkCacheFormat.PREAMBLE_LENGTH;
                state = TRANSFORM;
            } else {
                state = PASSTHROUGH;
            }
            return true;
        }
        if (readUpstream()) {
            return true;
        }
        state = PASSTHROUGH;
        return true;
    }

    private boolean stepTransform() throws IOException {
        decodeSink.reset();
        int consumed = ChunkCacheCodec.decodeBlock(accum, aStart, aEnd, cache, warm, store, decodeSink);
        if (consumed == ChunkCacheCodec.NEED_MORE) {
            if (readUpstream()) {
                return true;
            }
            if (aStart < aEnd) {
                throw new IOException("zstdnet chunk-cache: truncated block at EOF");
            }
            return false; // 干净地停在 block 边界
        }
        if (cacheStats != null) {
            // 此时 aStart 仍指向 block 类型字节（尚未前移），decodeSink 为本 block 还原出的原始帧。
            cacheStats.recordDecoded(accum[aStart] & 0xFF, consumed, decodeSink.size());
        }
        aStart += consumed;
        if (decodeSink.size() > 0) {
            out = decodeSink.toByteArray();
            oStart = 0;
            oEnd = out.length;
        }
        return true;
    }

    private boolean preambleMatches() {
        for (int i = 0; i < ChunkCacheFormat.PREAMBLE_MAGIC.length; i++) {
            if (accum[aStart + i] != ChunkCacheFormat.PREAMBLE_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private void serveAccum() {
        out = Arrays.copyOfRange(accum, aStart, aEnd);
        oStart = 0;
        oEnd = out.length;
        aStart = aEnd;
    }

    private boolean readUpstream() throws IOException {
        if (upstreamEof) {
            return false;
        }
        ensureAccumWritable(READ_CHUNK);
        int n;
        do {
            n = in.read(accum, aEnd, accum.length - aEnd);
        } while (n == 0);
        if (n < 0) {
            upstreamEof = true;
            return false;
        }
        aEnd += n;
        return true;
    }

    private void ensureAccumWritable(int min) {
        if (accum.length - aEnd >= min) {
            return;
        }
        compactAccum();
        if (accum.length - aEnd >= min) {
            return;
        }
        int needed = aEnd + min;
        int cap = accum.length;
        while (cap < needed) {
            cap <<= 1;
            if (cap < 0) {
                cap = needed;
                break;
            }
        }
        accum = Arrays.copyOf(accum, cap);
    }

    private void compactAccum() {
        if (aStart == 0) {
            return;
        }
        int len = aEnd - aStart;
        if (len > 0) {
            System.arraycopy(accum, aStart, accum, 0, len);
        }
        aStart = 0;
        aEnd = len;
    }
}
