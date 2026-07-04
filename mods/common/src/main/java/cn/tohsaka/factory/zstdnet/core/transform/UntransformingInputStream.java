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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 逆变换输入流：位于 ZSTD 解压器与游戏客户端之间。从解压流读出变换字节，按长度驱动增量解析出
 * block 并还原成原始 MC 帧字节，与原始流逐字节一致。能处理任意读边界（不依赖分隔符，按显式长度等待）。
 *
 * <p><b>MAGIC 自检 / passthrough：</b>流首若匹配 {@link TransformFormat#PREAMBLE_MAGIC} 则进入变换模式；
 * 否则透明原样透传——因此对端没启用/未升级变换时，本包装仍能正常工作。
 *
 * <p><b>fail-closed：</b>一旦进入变换模式后遇到非法块/损坏/EOF 截断，抛 {@link IOException} 中止连接，
 * 绝不把错位字节喂给游戏。
 */
public final class UntransformingInputStream extends InputStream {
    private static final int DETECTING = 0;
    private static final int TRANSFORM = 1;
    private static final int PASSTHROUGH = 2;

    private static final int READ_CHUNK = 16 * 1024;

    private final InputStream in;

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

    public UntransformingInputStream(InputStream in) {
        this.in = in;
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

    /** 确保 {@code out} 有可读字节；返回 false 表示已 EOF 且无更多数据。 */
    private boolean ensureOutput() throws IOException {
        while (oStart >= oEnd) {
            if (!step()) {
                return false;
            }
        }
        return true;
    }

    /** 推进一步：产出字节 / 切换状态 / 读上游 / 终止。返回 false 仅在干净 EOF 且无残留时。 */
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
                throw new IllegalStateException("zstdnet transform: bad state " + state);
        }
    }

    private boolean stepDetect() throws IOException {
        if (aEnd - aStart >= TransformFormat.PREAMBLE_LENGTH) {
            if (preambleMatches()) {
                version = accum[aStart + TransformFormat.PREAMBLE_MAGIC.length] & 0xFF;
                aStart += TransformFormat.PREAMBLE_LENGTH;
                state = TRANSFORM;
            } else {
                state = PASSTHROUGH;
            }
            return true;
        }
        // 还不够 5 字节判定：继续读；若提前 EOF，则全部按原样透传。
        if (readUpstream()) {
            return true;
        }
        state = PASSTHROUGH;
        return true;
    }

    private boolean stepTransform() throws IOException {
        decodeSink.reset();
        int consumed = StreamTransformCodec.decodeBlock(accum, aStart, aEnd, decodeSink);
        if (consumed == StreamTransformCodec.NEED_MORE) {
            if (readUpstream()) {
                return true;
            }
            if (aStart < aEnd) {
                throw new IOException("zstdnet transform: truncated block at EOF");
            }
            return false; // 干净地停在 block 边界
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
        for (int i = 0; i < TransformFormat.PREAMBLE_MAGIC.length; i++) {
            if (accum[aStart + i] != TransformFormat.PREAMBLE_MAGIC[i]) {
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
