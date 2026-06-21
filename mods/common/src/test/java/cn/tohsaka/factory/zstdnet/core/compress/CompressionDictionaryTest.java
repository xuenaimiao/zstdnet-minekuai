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

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictTrainer;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 LDM 与训练字典的核心机制：round-trip、windowLog≤27 的旧客户端兼容、帧 dict-id 协商、客户端自动缓存。
 */
class CompressionDictionaryTest {

    @Test
    void longDistanceMatchingFrameIsDecodableByDefaultDecoder() throws Exception {
        byte[] data = repetitivePayload(20_000);
        // windowLog 27：开 LDM 压缩
        CompressionOptions options = CompressionOptions.of(true, 27, null);
        byte[] frame = compress(data, 9, options, false);

        // 关键：用「默认」解码器（不调用 setLongMax）也能解出来，证明 windowLog≤27 对未升级客户端线兼容。
        byte[] decoded;
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            decoded = in.readAllBytes();
        }
        assertArrayEquals(data, decoded);
        // LDM 帧不带字典，dict id 为 0
        assertEquals(0L, peek(frame));
    }

    @Test
    void dictionaryRoundTripAndFrameDictIdNegotiation() throws Exception {
        byte[] dictionary = trainDictionary();
        long dictId = Zstd.getDictIdFromDict(dictionary);
        assertNotEquals(0L, dictId, "训练字典应带非零 dict id");

        CompressionOptions options = CompressionOptions.of(false, 0, dictionary);
        assertTrue(options.hasDictionary());
        assertEquals(dictId, options.dictionaryId());

        byte[] data = repetitivePayload(6_000);
        byte[] frame = compress(data, 9, options, true);

        // 服务端/客户端协商靠帧头 dict-id：peek 不消费即可读出 id。
        assertEquals(dictId, peek(frame));

        // peek 之后字节已退回，带字典解压应还原原文。
        PushbackInputStream pin = new PushbackInputStream(new ByteArrayInputStream(frame), ZstdStreams.FRAME_DICT_ID_PEEK);
        assertEquals(dictId, ZstdStreams.peekFrameDictId(pin));
        byte[] decoded;
        try (InputStream in = ZstdStreams.newDecompressor(pin, options, dictionary)) {
            decoded = in.readAllBytes();
        }
        assertArrayEquals(data, decoded);
    }

    @Test
    void noneOptionsProduceIdenticalPlainFrame() throws Exception {
        byte[] data = repetitivePayload(3_000);
        byte[] viaHelper = compress(data, 9, CompressionOptions.none(), false);

        // 直接用裸 ZstdOutputStream（历史路径）对照，验证 none() 不改变压缩输出。
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(raw, 9)) {
            out.setCloseFrameOnFlush(false);
            out.write(data);
        }
        assertArrayEquals(raw.toByteArray(), viaHelper);
        assertEquals(0L, peek(viaHelper));
    }

    @Test
    void clientDictionaryStoreCachesAndResolvesPerServer(@TempDir Path configDir) throws Exception {
        byte[] dictionary = trainDictionary();
        long dictId = Zstd.getDictIdFromDict(dictionary);
        String server = "Play.Example.com:25565";

        assertFalse(ClientDictionaryStore.hasDictionary(configDir, dictId));
        assertTrue(ClientDictionaryStore.store(configDir, server, dictId, dictionary));
        assertTrue(ClientDictionaryStore.hasDictionary(configDir, dictId));

        // 同一服务器（大小写无关）解析出缓存字典，并保留 base 的 LDM 设置。
        CompressionOptions base = CompressionOptions.of(true, 25, null);
        CompressionOptions resolved = ClientDictionaryStore.resolveFor(configDir, "play.example.com:25565", base);
        assertTrue(resolved.hasDictionary());
        assertEquals(dictId, resolved.dictionaryId());
        assertTrue(resolved.longDistanceMatching());

        // 未知服务器回退到 base（无字典）。
        assertFalse(ClientDictionaryStore.resolveFor(configDir, "other.server:25565", CompressionOptions.none()).hasDictionary());

        // 超过大小上限的字典被拒绝（防止恶意服务器塞爆磁盘）。
        byte[] oversized = new byte[(int) (ClientDictionaryStore.MAX_DICTIONARY_BYTES + 1)];
        assertFalse(ClientDictionaryStore.store(configDir, "x:1", dictId, oversized));
    }

    @Test
    void manualDictionaryOverridesAutoCache(@TempDir Path configDir) throws Exception {
        byte[] dictionary = trainDictionary();
        long dictId = Zstd.getDictIdFromDict(dictionary);
        ClientDictionaryStore.store(configDir, "srv:1", dictId, dictionary);

        // base 已带（手动）字典时，resolveFor 不应被自动缓存覆盖。
        CompressionOptions manual = CompressionOptions.of(false, 0, dictionary);
        CompressionOptions resolved = ClientDictionaryStore.resolveFor(configDir, "srv:1", manual);
        assertSame(manual, resolved);
    }

    @Test
    void autoSamplingChunksTrainFromFewConnections(@TempDir Path configDir) throws Exception {
        // dictionary_auto 的关键：每条连接被切成多个 16KB 样本块，所以「两三条连接」就能凑够
        // ZDICT 要求的最低样本数，小服(七八人)正常进服一次即可训练，无需任何人反复进出。
        Path samplesDir = configDir.resolve("samples");
        DictionarySampler sampler = new DictionarySampler(samplesDir);
        byte[] loginBurst = repetitivePayload(8_000); // > 128KB，单连接会被截到 128KB = 8 个块

        for (int connection = 0; connection < 3; connection++) {
            DictionarySampler.Collector collector = sampler.newCollector();
            assertNotNull(collector);
            collector.accept(loginBurst, 0, loginBurst.length);
            collector.finish();
        }

        // 3 条连接 × 8 块 = 24 个样本文件（≥ ZDICT 下限 12），应能训出有效字典。
        try (var stream = java.nio.file.Files.newDirectoryStream(samplesDir, "*.bin")) {
            int files = 0;
            for (var ignored : stream) {
                files++;
            }
            assertTrue(files >= 12, "chunked sampling should yield enough samples from a few connections, got " + files);
        }

        byte[] dictionary = DictionaryTrainer.train(samplesDir, 64 * 1024);
        assertNotNull(dictionary, "a few connections' worth of chunked samples should train a dictionary");
        assertTrue(dictionary.length > 0);
        assertNotEquals(0L, Zstd.getDictIdFromDict(dictionary));
    }

    // ---- helpers ----

    private static byte[] compress(byte[] data, int level, CompressionOptions options, boolean useDictionary) throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (OutputStream out = ZstdStreams.newCompressor(wire, level, options, useDictionary)) {
            out.write(data);
        }
        return wire.toByteArray();
    }

    private static long peek(byte[] frame) throws Exception {
        PushbackInputStream pin = new PushbackInputStream(new ByteArrayInputStream(frame), ZstdStreams.FRAME_DICT_ID_PEEK);
        return ZstdStreams.peekFrameDictId(pin);
    }

    private static byte[] repetitivePayload(int rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < rows; i++) {
            String line = "minecraft:stone#" + (i % 64) + ";create:cogwheel;entityMove(" + (i % 128) + ");blockUpdate\n";
            out.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static byte[] trainDictionary() {
        ZstdDictTrainer trainer = new ZstdDictTrainer(4 * 1024 * 1024, 16 * 1024);
        Random rnd = new Random(1234);
        String[] tokens = {
            "minecraft:stone", "minecraft:dirt", "create:cogwheel", "create:shaft",
            "entityMove", "blockUpdate", "chunkData", "nbt:Items", "Damage", "Count"
        };
        for (int s = 0; s < 5000; s++) {
            StringBuilder sb = new StringBuilder();
            int len = 10 + rnd.nextInt(20);
            for (int j = 0; j < len; j++) {
                sb.append(tokens[rnd.nextInt(tokens.length)]).append(':').append(rnd.nextInt(256)).append(';');
            }
            trainer.addSample(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return trainer.trainSamples();
    }
}
