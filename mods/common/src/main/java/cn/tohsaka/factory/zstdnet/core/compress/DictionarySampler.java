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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 字典训练语料采样器（仅在 {@code dictionary_capture=true} 时启用）。
 * <p>
 * 每条连接采样开头的下行（backend→client）原文若干 KB——这正是登录初始 registry/tag/recipe 爆发，
 * 也是字典最该针对的内容——写入 {@code config/zstdnet/dict/samples/}。带每连接与全局上限，
 * 制作完字典后请关闭采样。
 */
public final class DictionarySampler {
    /** 每条连接最多采样的字节数（只取连接开头，足以覆盖登录爆发）。 */
    private static final int PER_CONNECTION_BYTES = 128 * 1024;
    /**
     * 把每条连接的采样切成这么大的「样本块」分别落盘。
     * <p>ZSTD 字典训练（ZDICT/COVER）按<b>样本个数</b>计，原生要求至少十余个样本，否则报 “nb of samples too low”。
     * 一条连接只产一个样本时，小服（七八人各进一次）永远凑不够；切成多块后，两三条连接即可达标——
     * 而登录爆发高度自相似，分块训练同样能训出好字典。
     */
    private static final int SAMPLE_CHUNK_BYTES = 16 * 1024;
    /** 小于此值的尾块丢弃，避免产生过碎的样本。 */
    private static final int MIN_CHUNK_BYTES = 4 * 1024;
    /** 全局语料总量上限。 */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    /** 全局样本文件数量上限。 */
    private static final int MAX_FILES = 4000;

    private final Path samplesDir;
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger fileCount = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();

    public DictionarySampler(Path samplesDir) {
        this.samplesDir = samplesDir;
    }

    private boolean capExceeded() {
        return fileCount.get() >= MAX_FILES || totalBytes.get() >= MAX_TOTAL_BYTES;
    }

    /** 为一条连接开一个采样器；达到上限时返回 null（不再采样）。 */
    public Collector newCollector() {
        return capExceeded() ? null : new Collector();
    }

    /** 单连接采样缓冲（非线程安全：仅由该连接的转发线程使用）。 */
    public final class Collector {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public void accept(byte[] data, int offset, int length) {
            int remaining = PER_CONNECTION_BYTES - buffer.size();
            if (remaining <= 0 || length <= 0) {
                return;
            }
            buffer.write(data, offset, Math.min(length, remaining));
        }

        public void finish() {
            if (buffer.size() == 0 || capExceeded()) {
                return;
            }
            try {
                Files.createDirectories(samplesDir);
                byte[] data = buffer.toByteArray();
                long stamp = System.nanoTime();
                // 切成多个样本块分别落盘（见 SAMPLE_CHUNK_BYTES 注释）。
                for (int offset = 0; offset < data.length; offset += SAMPLE_CHUNK_BYTES) {
                    int len = Math.min(SAMPLE_CHUNK_BYTES, data.length - offset);
                    if (len < MIN_CHUNK_BYTES && offset > 0) {
                        break; // 丢弃过碎的尾块
                    }
                    if (fileCount.get() >= MAX_FILES || totalBytes.get() >= MAX_TOTAL_BYTES) {
                        break;
                    }
                    Path file = samplesDir.resolve("sample-" + stamp + "-" + sequence.incrementAndGet() + ".bin");
                    Files.write(file, java.util.Arrays.copyOfRange(data, offset, offset + len));
                    totalBytes.addAndGet(len);
                    fileCount.incrementAndGet();
                }
            } catch (IOException ignored) {
                // 采样失败不影响正常转发
            }
        }
    }
}
