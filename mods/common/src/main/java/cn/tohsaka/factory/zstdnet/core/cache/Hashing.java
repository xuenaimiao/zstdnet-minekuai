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

/**
 * 内容寻址用的内容哈希（CRC 区块引用缓存 / 测量共用）。FNV-1a 累加 + murmur3 finalizer 混合改善雪崩，
 * 纯函数、无依赖、确定性——同一输入恒得同一哈希值（这是 REF 令牌与去重统计正确性的基础）。
 *
 * <p>用途为去重 / 内容指纹，<b>非</b>加密哈希。
 *
 * <p><b>两种宽度，用途不同：</b>
 * <ul>
 *   <li>{@link #content64}（8 字节）——<b>会话内</b> REF 令牌。会话内编/解码两端处于 lock-step（同一 FULL 字节、
 *       同步淘汰），服务端发 REF 前另做<b>整字节比对</b>规避碰撞，故 64 位足矣。</li>
 *   <li>{@link #content128}（16 字节）——<b>跨会话</b> WARM_REF 令牌 / 磁盘持久化键。跨会话时服务端手里没有字节、
 *       无法比对，只能信任“同哈希 ⟹ 同字节”，故须用 128 位令碰撞在密码学意义上可忽略（与 BO 用 SHA-256 同理）。
 *       客户端磁盘条目另在加载时重算 content128 校验完整性，损坏即丢弃 → 不会以错字节服务 WARM_REF。</li>
 * </ul>
 * <b>约定：</b>{@code content128(x).hi() == content64(x)}（{@code hi} 半与 64 位算法逐位一致），
 * 故编码端只算一次 content128 即同时得到会话内 8 字节键（hi）与跨会话 16 字节键（hi+lo）。
 */
public final class Hashing {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    // 第二路（lo 半）独立的基/质数：与第一路不同 → 两路近似独立 → 拼成约 128 位强度。
    private static final long FNV_OFFSET_2 = 0x9e3779b97f4a7c15L;
    private static final long FNV_PRIME_2 = 0xff51afd7ed558ccdL;

    private Hashing() {
    }

    /** 对 {@code data[off, off+len)} 计算 64 位内容哈希。 */
    public static long content64(byte[] data, int off, int len) {
        long h = FNV_OFFSET;
        int end = off + len;
        for (int i = off; i < end; i++) {
            h ^= (data[i] & 0xFFL);
            h *= FNV_PRIME;
        }
        return fmix64(h);
    }

    /** 对整个数组计算 64 位内容哈希。 */
    public static long content64(byte[] data) {
        return content64(data, 0, data.length);
    }

    /**
     * 对 {@code data[off, off+len)} 计算 128 位内容哈希。{@code hi} 半与 {@link #content64} 逐位一致
     * （同一遍累加），{@code lo} 半用独立基/质数 → 拼成约 128 位指纹。
     */
    public static Hash128 content128(byte[] data, int off, int len) {
        long h1 = FNV_OFFSET;
        long h2 = FNV_OFFSET_2;
        int end = off + len;
        for (int i = off; i < end; i++) {
            long b = data[i] & 0xFFL;
            h1 ^= b;
            h1 *= FNV_PRIME;
            h2 ^= b;
            h2 *= FNV_PRIME_2;
        }
        return new Hash128(fmix64(h1), fmix64(h2));
    }

    /** 对整个数组计算 128 位内容哈希。 */
    public static Hash128 content128(byte[] data) {
        return content128(data, 0, data.length);
    }

    /** murmur3 64 位 finalizer：FNV-1a 雪崩偏弱，这步把比特充分扩散开。 */
    private static long fmix64(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
