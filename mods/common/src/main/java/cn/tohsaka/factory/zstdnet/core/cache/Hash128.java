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

import java.util.Objects;

/**
 * 128 位内容哈希值（{@code hi} + {@code lo}），用作<b>跨会话</b> WARM_REF 令牌与磁盘缓存键。
 * 不可变值类型，自带 {@code equals}/{@code hashCode}，可直接做 {@code Map} 键。
 *
 * <p>线路上以 16 字节大端表示（{@code hi} 在前、{@code lo} 在后）；磁盘文件名以 32 位十六进制小写表示。
 * 见 {@link Hashing#content128} 的宽度用途说明。
 */
public final class Hash128 {

    private final long hi;
    private final long lo;

    public Hash128(long hi, long lo) {
        this.hi = hi;
        this.lo = lo;
    }

    /** {@code hi} 高 64 位。 */
    public long hi() {
        return this.hi;
    }

    /** {@code lo} 低 64 位。 */
    public long lo() {
        return this.lo;
    }

    /** 16 字节大端编码（{@code hi} 高 8 字节，{@code lo} 低 8 字节）。 */
    public byte[] toBytes() {
        byte[] out = new byte[16];
        writeBytes(out, 0);
        return out;
    }

    /** 把本值的 16 字节大端表示写入 {@code dst[off, off+16)}。 */
    public void writeBytes(byte[] dst, int off) {
        for (int i = 0; i < 8; i++) {
            dst[off + i] = (byte) (hi >>> (56 - 8 * i));
            dst[off + 8 + i] = (byte) (lo >>> (56 - 8 * i));
        }
    }

    /** 从 {@code src[off, off+16)} 大端解出。 */
    public static Hash128 fromBytes(byte[] src, int off) {
        long h = 0L;
        long l = 0L;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (src[off + i] & 0xFFL);
            l = (l << 8) | (src[off + 8 + i] & 0xFFL);
        }
        return new Hash128(h, l);
    }

    /** 32 位十六进制小写（{@code hi} 在前），用作磁盘文件名。 */
    public String toHex() {
        return String.format("%016x%016x", hi, lo);
    }

    /** 解析 {@link #toHex} 的 32 位十六进制；非法长度 / 非十六进制返回 {@code null}（用于跳过损坏文件名）。 */
    public static Hash128 fromHex(String hex) {
        if (hex == null || hex.length() != 32) {
            return null;
        }
        try {
            long h = Long.parseUnsignedLong(hex.substring(0, 16), 16);
            long l = Long.parseUnsignedLong(hex.substring(16, 32), 16);
            return new Hash128(h, l);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hash128)) {
            return false;
        }
        Hash128 other = (Hash128) o;
        return this.hi == other.hi && this.lo == other.lo;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hi, lo);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
