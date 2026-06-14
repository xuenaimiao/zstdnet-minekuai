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

package cn.tohsaka.factory.zstdnet.core.transform;

/**
 * 不可变变换参数。仿 {@code CompressionOptions} 的纪律：默认 {@link #disabled()} 表示<b>完全不启用</b>，
 * 此时调用方不应安装任何变换/逆变换包装，喂入/解出 ZSTD 的字节与历史逐字节一致。
 *
 * <ul>
 *     <li>{@code enabled}：本端是否参与变换（服务端=是否对下行变换；客户端=是否 advertise 并安装逆向包装）。</li>
 *     <li>{@code maxVersion}：本端支持/期望的最高变换版本；连接最终生效版本取两端 min。</li>
 *     <li>{@code coalesceMs}：把多个 flush 窗口合并成更大 block 的上限毫秒（0=关，默认）。</li>
 * </ul>
 */
public final class TransformOptions {
    private static final TransformOptions DISABLED = new TransformOptions(false, 0, 0);

    private final boolean enabled;
    private final int maxVersion;
    private final int coalesceMs;

    private TransformOptions(boolean enabled, int maxVersion, int coalesceMs) {
        this.enabled = enabled;
        this.maxVersion = maxVersion;
        this.coalesceMs = coalesceMs;
    }

    /** 不启用变换（默认；与历史行为逐字节一致）。 */
    public static TransformOptions disabled() {
        return DISABLED;
    }

    /**
     * @param maxVersion 期望最高版本；会被夹到 {@code [1, MAX_SUPPORTED_VERSION]}
     * @param coalesceMs 合并窗口毫秒；负数归零
     */
    public static TransformOptions enabled(int maxVersion, int coalesceMs) {
        int v = Math.max(TransformFormat.VERSION_LAYER_A,
            Math.min(TransformFormat.MAX_SUPPORTED_VERSION, maxVersion));
        return new TransformOptions(true, v, Math.max(0, coalesceMs));
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxVersion() {
        return maxVersion;
    }

    public int coalesceMs() {
        return coalesceMs;
    }
}
