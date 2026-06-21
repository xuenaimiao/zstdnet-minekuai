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

package cn.tohsaka.factory.zstdnet.core.compress;

/**
 * 不可变压缩参数：在基础 ZSTD level 之外附加可选的「长距离匹配(LDM)」与「训练字典」。
 * <p>
 * 设计要点：
 * <ul>
 *     <li>全部默认关闭。{@link #none()} 表示与历史行为<b>逐字节一致</b>——既不调用
 *         {@code setLong} 也不调用 {@code setDict}，压缩输出与旧版完全相同。</li>
 *     <li>{@code level} 不在此处：服务端用 {@code zstdnet-server.properties} 的 level，
 *         客户端用 {@code zstdnet-client.toml} 的 level，二者语义不同，保持各自独立。</li>
 *     <li>字典 id 由字典内容算出（{@link ZstdCodecs#getDictIdFromDict(byte[])}），用于连接建立时的
 *         隐式协商：训练字典会把该 id 写进每个压缩帧头，对端无需持有字典即可读出。</li>
 * </ul>
 */
public final class CompressionOptions {
    /**
     * ZSTD 流式解码器默认窗口上限（{@code ZSTD_WINDOWLOG_LIMIT_DEFAULT}）。
     * windowLog ≤ 此值的帧，任何标准 ZSTD 解码器（含未升级的旧客户端）都能解，
     * 因此 windowLog ≤ 27 的服务端 LDM 对现有客户端线兼容。
     */
    public static final int DEFAULT_DECOMPRESS_WINDOW_LOG_MAX = 27;

    private static final int MIN_WINDOW_LOG = 10;
    private static final int MAX_WINDOW_LOG = 31;
    /** LDM 开启但未显式指定 window_log 时的保守默认（≈16MiB 窗口）。 */
    private static final int DEFAULT_LDM_WINDOW_LOG = 24;

    private static final CompressionOptions NONE = new CompressionOptions(false, 0, null);

    private final boolean longDistanceMatching;
    private final int windowLog;
    private final byte[] dictionary;
    private final long dictionaryId;

    private CompressionOptions(boolean longDistanceMatching, int windowLog, byte[] dictionary) {
        this.longDistanceMatching = longDistanceMatching;
        this.windowLog = windowLog;
        this.dictionary = dictionary != null && dictionary.length > 0 ? dictionary : null;
        this.dictionaryId = this.dictionary == null ? 0L : ZstdCodecs.getDictIdFromDict(this.dictionary);
    }

    /** 不附加任何额外参数（与历史行为逐字节一致）。 */
    public static CompressionOptions none() {
        return NONE;
    }

    /**
     * @param longDistanceMatching 是否启用长距离匹配
     * @param windowLog            LDM 窗口（0 表示用保守默认；最终会被夹到 [10,31]）
     * @param dictionary           训练字典原始字节，null/空表示无字典
     */
    public static CompressionOptions of(boolean longDistanceMatching, int windowLog, byte[] dictionary) {
        boolean hasDict = dictionary != null && dictionary.length > 0;
        if (!longDistanceMatching && !hasDict) {
            return NONE;
        }
        return new CompressionOptions(longDistanceMatching, windowLog, dictionary);
    }

    public boolean longDistanceMatching() {
        return longDistanceMatching;
    }

    /** LDM 实际使用的 windowLog；LDM 关闭时返回 0（调用方据此决定是否 setLong）。 */
    public int effectiveWindowLog() {
        if (!longDistanceMatching) {
            return 0;
        }
        int wl = windowLog > 0 ? windowLog : DEFAULT_LDM_WINDOW_LOG;
        return Math.max(MIN_WINDOW_LOG, Math.min(MAX_WINDOW_LOG, wl));
    }

    public boolean hasDictionary() {
        return dictionary != null;
    }

    /** 字典原始字节（只读，调用方<b>不得</b>修改）；无字典时为 null。 */
    public byte[] dictionary() {
        return dictionary;
    }

    /** 字典 id（0 表示无字典）。 */
    public long dictionaryId() {
        return dictionaryId;
    }

    /**
     * 解码器需要放开的窗口上限：仅当本端 LDM 窗口超过默认 27 时才需要抬高，
     * 否则保持 27（默认行为，不触碰解码器）。
     */
    public int decompressWindowLogMax() {
        int wl = effectiveWindowLog();
        return wl > DEFAULT_DECOMPRESS_WINDOW_LOG_MAX ? wl : DEFAULT_DECOMPRESS_WINDOW_LOG_MAX;
    }
}
