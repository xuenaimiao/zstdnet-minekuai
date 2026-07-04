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

package cn.tohsaka.factory.zstdnet.core.cache.patch;

import cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheFormat;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 结构无关的字节级增量编解码（CRC PATCH 的底层原语）。把 {@code target} 表达成相对 {@code base} 的一串
 * copy/insert 算子；{@link #apply} 在另一端从同一 {@code base} 精确重建出 {@code target}。
 *
 * <p><b>版本无关、两端对称：</b>{@link #diff} 仅编码端用、{@link #apply} 仅解码端用，二者都<b>不</b>解析任何
 * Minecraft 区块结构——纯字节算法。因此把 MC 协议的版本脆弱性完全挡在 PATCH 之外：增量怎么切都不影响正确性，
 * 只影响大小。调用方（{@link cn.tohsaka.factory.zstdnet.core.cache.ChunkCacheCodec}）在 {@link #apply} 之后
 * 另做一道 {@code content128(结果).hi()==NEW} 的哈希校验门，任何重建偏差都会被挡下并 fail-closed。
 *
 * <p>算子流（{@code DELTA} 载荷，长度由外层 block 头给出）：
 * <pre>
 *   OP_COPY(0x01):   SRC_OFF(varint) LEN(varint)   —— 从 base[SRC_OFF, SRC_OFF+LEN) 复制
 *   OP_INSERT(0x02): LEN(varint) LITERAL[LEN]      —— 插入 LEN 个字面字节
 * </pre>
 *
 * <p>{@link #apply} 对每个算子做严格边界检查（SRC 越界 / 长度为负 / 结果超 {@link ChunkCacheFormat#MAX_FRAME_LENGTH}
 * / 截断），任一不符抛 {@link IOException}（fail-closed），绝不产出错位字节。
 */
public final class DeltaCodec {
    static final int OP_COPY = 0x01;
    static final int OP_INSERT = 0x02;

    /** 用于建索引的种子长度（4 字节）。 */
    private static final int SEED = 4;

    private DeltaCodec() {
    }

    // ---- 编码端：diff ----

    /**
     * 计算把 {@code base} 变成 {@code target} 的字节级增量。纯函数、确定性。贪心最长匹配：在 {@code base} 上对
     * 每个 4 字节种子建“最近出现位置”索引，扫描 {@code target} 时遇到长度 ≥ {@link ChunkCacheFormat#PATCH_MIN_MATCH}
     * 的匹配就发 COPY，其余累积为 INSERT 字面量。{@code apply(base, diff(base, target))} 恒等于 {@code target}。
     */
    public static byte[] diff(byte[] base, byte[] target) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, target.length / 2 + 8));
        int bn = base.length;
        int tn = target.length;
        int minMatch = Math.max(SEED, ChunkCacheFormat.PATCH_MIN_MATCH);

        // base 4 字节种子 → 最近出现位置（单条目链；哈希碰撞在使用前用真字节校验）。
        int[] table = null;
        int mask = 0;
        if (bn >= SEED) {
            int size = tableSize(bn);
            mask = size - 1;
            table = new int[size];
            Arrays.fill(table, -1);
            for (int i = 0; i + SEED <= bn; i++) {
                table[hash(base, i) & mask] = i;
            }
        }

        int litStart = 0;
        int i = 0;
        while (table != null && i + SEED <= tn) {
            int cand = table[hash(target, i) & mask];
            int matchLen = 0;
            int matchSrc = -1;
            if (cand >= 0) {
                int len = matchLength(base, cand, target, i);
                if (len >= minMatch) {
                    matchLen = len;
                    matchSrc = cand;
                }
            }
            if (matchLen >= minMatch) {
                if (i > litStart) {
                    writeInsert(out, target, litStart, i - litStart);
                }
                writeCopy(out, matchSrc, matchLen);
                i += matchLen;
                litStart = i;
            } else {
                i++;
            }
        }
        if (tn > litStart) {
            writeInsert(out, target, litStart, tn - litStart);
        }
        return out.toByteArray();
    }

    // ---- 解码端：apply ----

    /**
     * 从 {@code base} 与 {@code delta} 精确重建原帧。严格边界检查，任何越界 / 非法算子 / 截断 → {@link IOException}。
     */
    public static byte[] apply(byte[] base, byte[] delta) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, base.length));
        int dn = delta.length;
        int pos = 0;
        while (pos < dn) {
            int op = delta[pos++] & 0xFF;
            if (op == OP_COPY) {
                VarIntRead srcRead = VarIntCodec.read(delta, pos, dn);
                if (srcRead == null) {
                    throw new IOException("zstdnet chunk-cache: truncated PATCH copy src");
                }
                int src = srcRead.value();
                pos = srcRead.next();
                VarIntRead lenRead = VarIntCodec.read(delta, pos, dn);
                if (lenRead == null) {
                    throw new IOException("zstdnet chunk-cache: truncated PATCH copy len");
                }
                int len = lenRead.value();
                pos = lenRead.next();
                if (src < 0 || len < 0 || (long) src + len > base.length) {
                    throw new IOException("zstdnet chunk-cache: PATCH copy out of base range (src=" + src + ", len=" + len + ")");
                }
                if (out.size() + (long) len > ChunkCacheFormat.MAX_FRAME_LENGTH) {
                    throw new IOException("zstdnet chunk-cache: PATCH result exceeds frame limit");
                }
                out.write(base, src, len);
            } else if (op == OP_INSERT) {
                VarIntRead lenRead = VarIntCodec.read(delta, pos, dn);
                if (lenRead == null) {
                    throw new IOException("zstdnet chunk-cache: truncated PATCH insert len");
                }
                int len = lenRead.value();
                pos = lenRead.next();
                if (len < 0 || pos + (long) len > dn) {
                    throw new IOException("zstdnet chunk-cache: PATCH insert out of delta range (len=" + len + ")");
                }
                if (out.size() + (long) len > ChunkCacheFormat.MAX_FRAME_LENGTH) {
                    throw new IOException("zstdnet chunk-cache: PATCH result exceeds frame limit");
                }
                out.write(delta, pos, len);
                pos += len;
            } else {
                throw new IOException("zstdnet chunk-cache: unknown PATCH op " + op);
            }
        }
        return out.toByteArray();
    }

    // ---- 内部 ----

    private static void writeCopy(ByteArrayOutputStream out, int srcOff, int len) {
        out.write(OP_COPY);
        byte[] srcOffBytes = VarIntCodec.encode(srcOff);
        out.write(srcOffBytes, 0, srcOffBytes.length);
        byte[] lenBytes = VarIntCodec.encode(len);
        out.write(lenBytes, 0, lenBytes.length);
    }

    private static void writeInsert(ByteArrayOutputStream out, byte[] src, int off, int len) {
        out.write(OP_INSERT);
        byte[] lenBytes = VarIntCodec.encode(len);
        out.write(lenBytes, 0, lenBytes.length);
        out.write(src, off, len);
    }

    /** base[bi..] 与 target[ti..] 的公共前缀长度（不超出各自数组）。 */
    private static int matchLength(byte[] base, int bi, byte[] target, int ti) {
        int max = Math.min(base.length - bi, target.length - ti);
        int k = 0;
        while (k < max && base[bi + k] == target[ti + k]) {
            k++;
        }
        return k;
    }

    /** 4 字节种子的哈希（与具体字节强相关即可；碰撞由真字节校验兜底）。 */
    private static int hash(byte[] data, int off) {
        int h = (data[off] & 0xFF)
            | ((data[off + 1] & 0xFF) << 8)
            | ((data[off + 2] & 0xFF) << 16)
            | ((data[off + 3] & 0xFF) << 24);
        h *= 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    /** 取 ≥ base 长度的 2 的幂（上限 2^20，控内存）。 */
    private static int tableSize(int baseLen) {
        int cap = 1;
        int target = Math.min(baseLen, 1 << 20);
        while (cap < target) {
            cap <<= 1;
        }
        return Math.max(cap, 16);
    }
}
