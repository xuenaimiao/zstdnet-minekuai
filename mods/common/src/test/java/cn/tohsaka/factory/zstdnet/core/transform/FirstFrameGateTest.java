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

package cn.tohsaka.factory.zstdnet.core.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PacketFramer#peekFirstPacketId()} 与 {@link FirstFrameGate}：首帧前缀的各种到达形态。
 */
class FirstFrameGateTest {

    private static PacketFramer framerWith(int... bytes) {
        PacketFramer framer = new PacketFramer();
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        framer.append(data, 0, data.length);
        return framer;
    }

    @Test
    void emptyBufferNeedsMore() {
        assertEquals(PacketFramer.FIRST_NEED_MORE, new PacketFramer().peekFirstPacketId());
        assertEquals(FirstFrameGate.Verdict.UNDECIDED, FirstFrameGate.decide(new PacketFramer()));
    }

    @Test
    void lengthPrefixOnlyNeedsMore() {
        // 只到了长度前缀（0x78 = 120），packetId 还没来。
        assertEquals(PacketFramer.FIRST_NEED_MORE, framerWith(0x78).peekFirstPacketId());
        // 2 字节长度前缀只到一半（0x80 带续位）。
        assertEquals(PacketFramer.FIRST_NEED_MORE, framerWith(0x80).peekFirstPacketId());
    }

    @Test
    void readsPacketIdWithoutNeedingTheWholeFrame() {
        // 声明 120 字节，只到了前 2 字节：packetId=0x01 已可读。
        assertEquals(0x01, framerWith(0x78, 0x01).peekFirstPacketId());
        assertEquals(0x03, framerWith(0x03, 0x03, 0x80, 0x80, 0x40).peekFirstPacketId());
    }

    @Test
    void multiBytePacketIdNeedsAllItsBytes() {
        // packetId 的 VarInt 只到了 0x81（续位）——要等下一字节。
        assertEquals(PacketFramer.FIRST_NEED_MORE, framerWith(0x78, 0x81).peekFirstPacketId());
        assertEquals(0x81 & 0x7F | (0x01 << 7), framerWith(0x78, 0x81, 0x01).peekFirstPacketId());
    }

    @Test
    void invalidLengthPrefixIsNotAFrame() {
        // 5 个连续续位字节：不是合法 VarInt。
        assertEquals(PacketFramer.FIRST_NOT_A_FRAME, framerWith(0xFF, 0xFF, 0xFF, 0xFF, 0xFF).peekFirstPacketId());
    }

    @Test
    void emptyFirstFrameIsStillAFrame() {
        // 长度 0 是帧格式允许的空帧（原版不发，但编解码器支持）：没有 packetId，不是加密请求，照常切帧。
        assertEquals(PacketFramer.FIRST_EMPTY_FRAME, framerWith(0x00, 0x01).peekFirstPacketId());
        assertEquals(FirstFrameGate.Verdict.FRAMED, FirstFrameGate.decide(framerWith(0x00)));
    }

    @Test
    void absurdFrameLengthIsNotAFrame() {
        // 声明 > 16 MiB：绝不是正常的首帧。0xFF 0xFF 0xFF 0x7F = 268435455。
        assertEquals(PacketFramer.FIRST_NOT_A_FRAME, framerWith(0xFF, 0xFF, 0xFF, 0x7F, 0x01).peekFirstPacketId());
    }

    @Test
    void packetIdVarIntTooLongIsNotAFrame() {
        assertEquals(PacketFramer.FIRST_NOT_A_FRAME, framerWith(0x78, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF).peekFirstPacketId());
    }

    @Test
    void peekDoesNotConsumeBytes() {
        PacketFramer framer = framerWith(0x03, 0x03, 0x00, 0x00);
        assertEquals(0x03, framer.peekFirstPacketId());
        assertEquals(0x03, framer.peekFirstPacketId());
        // 整帧仍可被正常切出。
        PacketFramer.Batch batch = null;
        try {
            batch = framer.peelComplete();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, batch.count);
    }

    @Test
    void gateVerdicts() {
        // Encryption Request → 其后是密文，不再切帧。
        assertEquals(FirstFrameGate.Verdict.UNFRAMED, FirstFrameGate.decide(framerWith(0x78, 0x01)));
        // 离线登录的首帧：Disconnect / Set Compression / Login Plugin Request → 照常切帧。
        assertEquals(FirstFrameGate.Verdict.FRAMED, FirstFrameGate.decide(framerWith(0x10, 0x00)));
        assertEquals(FirstFrameGate.Verdict.FRAMED, FirstFrameGate.decide(framerWith(0x05, 0x03)));
        assertEquals(FirstFrameGate.Verdict.FRAMED, FirstFrameGate.decide(framerWith(0x20, 0x04)));
        // 根本不是 MC 帧。
        assertEquals(FirstFrameGate.Verdict.UNFRAMED, FirstFrameGate.decide(framerWith(0xFF, 0xFF, 0xFF, 0xFF, 0xFF)));
        // 字节还不够 → 暂不判定。
        assertEquals(FirstFrameGate.Verdict.UNDECIDED, FirstFrameGate.decide(framerWith(0x78)));
    }
}
