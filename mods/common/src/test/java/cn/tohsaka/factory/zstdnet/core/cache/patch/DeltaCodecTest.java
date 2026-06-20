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

package cn.tohsaka.factory.zstdnet.core.cache.patch;

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeltaCodec}：{@code apply(base, diff(base, target)) == target} 在各种输入下恒成立（含模糊测试），
 * 小改动产出的增量确实远小于全帧，且 {@link DeltaCodec#apply} 对损坏/越界增量 fail-closed。
 */
class DeltaCodecTest {

    @Test
    void roundTripsAcrossManyRandomPairs() throws IOException {
        Random rnd = new Random(20260621L);
        for (int iter = 0; iter < 400; iter++) {
            byte[] base = randomBytes(rnd, rnd.nextInt(4000));
            byte[] target = mutate(rnd, base);
            byte[] delta = DeltaCodec.diff(base, target);
            assertArrayEquals(target, DeltaCodec.apply(base, delta),
                "round-trip failed at iter=" + iter + " base=" + base.length + " target=" + target.length);
        }
    }

    @Test
    void identicalTargetProducesTinyDelta() throws IOException {
        byte[] base = randomBytes(new Random(1L), 8000);
        byte[] delta = DeltaCodec.diff(base, base);
        assertArrayEquals(base, DeltaCodec.apply(base, delta));
        assertTrue(delta.length < base.length / 4, "identical content should compress to ~1 COPY; delta=" + delta.length);
    }

    @Test
    void smallEditProducesSmallDelta() throws IOException {
        byte[] base = randomBytes(new Random(2L), 8000);
        byte[] target = base.clone();
        // 改动中间 16 字节（模拟“放置一个方块”），其余不变。
        for (int i = 4000; i < 4016; i++) {
            target[i] = (byte) (target[i] ^ 0xFF);
        }
        byte[] delta = DeltaCodec.diff(base, target);
        assertArrayEquals(target, DeltaCodec.apply(base, delta));
        assertTrue(delta.length < base.length / 2, "localized edit should yield a small delta; delta=" + delta.length);
    }

    @Test
    void emptyBaseIsAllLiteralAndRoundTrips() throws IOException {
        byte[] base = new byte[0];
        byte[] target = randomBytes(new Random(3L), 500);
        byte[] delta = DeltaCodec.diff(base, target);
        assertArrayEquals(target, DeltaCodec.apply(base, delta));
    }

    @Test
    void emptyTargetProducesEmptyResult() throws IOException {
        byte[] base = randomBytes(new Random(4L), 500);
        byte[] delta = DeltaCodec.diff(base, new byte[0]);
        assertArrayEquals(new byte[0], DeltaCodec.apply(base, delta));
    }

    @Test
    void applyRejectsCopyOutOfBaseRange() {
        byte[] base = new byte[10];
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        delta.write(DeltaCodec.OP_COPY);
        delta.writeBytes(VarIntCodec.encode(5));   // srcOff=5
        delta.writeBytes(VarIntCodec.encode(100)); // len=100 → 5+100 > 10
        assertThrows(IOException.class, () -> DeltaCodec.apply(base, delta.toByteArray()));
    }

    @Test
    void applyRejectsInsertOverrun() {
        byte[] base = new byte[10];
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        delta.write(DeltaCodec.OP_INSERT);
        delta.writeBytes(VarIntCodec.encode(50)); // 声称 50 字面字节，但后面没有
        delta.write(1);
        delta.write(2);
        assertThrows(IOException.class, () -> DeltaCodec.apply(base, delta.toByteArray()));
    }

    @Test
    void applyRejectsUnknownOp() {
        byte[] base = new byte[10];
        byte[] delta = {(byte) 0x7F, 0x00};
        assertThrows(IOException.class, () -> DeltaCodec.apply(base, delta));
    }

    @Test
    void applyRejectsTruncatedCopyLen() {
        byte[] base = new byte[10];
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        delta.write(DeltaCodec.OP_COPY);
        delta.writeBytes(VarIntCodec.encode(0)); // srcOff=0, 然后缺 len
        assertThrows(IOException.class, () -> DeltaCodec.apply(base, delta.toByteArray()));
    }

    // ---- helpers ----

    /** 基于 base 派生一个“相似但不同”的 target：随机保留大段、插入/翻转一些片段，覆盖各种 diff 分支。 */
    private static byte[] mutate(Random rnd, byte[] base) {
        if (base.length == 0) {
            return randomBytes(rnd, rnd.nextInt(200));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < base.length) {
            int choice = rnd.nextInt(10);
            if (choice < 6) { // 保留一段（→ COPY）
                int run = 1 + rnd.nextInt(Math.max(1, base.length - i));
                out.write(base, i, run);
                i += run;
            } else if (choice < 8) { // 跳过一段（删除）
                i += 1 + rnd.nextInt(Math.max(1, (base.length - i) / 2 + 1));
            } else { // 插入随机字面量
                out.writeBytes(randomBytes(rnd, 1 + rnd.nextInt(20)));
            }
        }
        if (rnd.nextBoolean()) {
            out.writeBytes(randomBytes(rnd, rnd.nextInt(40)));
        }
        return out.toByteArray();
    }

    private static byte[] randomBytes(Random rnd, int len) {
        byte[] b = new byte[len];
        rnd.nextBytes(b);
        return b;
    }
}
