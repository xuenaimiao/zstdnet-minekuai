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

import java.io.IOException;
import java.io.OutputStream;

/**
 * 变换输出流：位于代理写循环与 ZSTD 压缩器之间。调用方把后端原始字节写进来，本流用
 * {@link PacketFramer} 切出完整 MC 帧、在 {@link #flush()} 时编码成一个 block 写入底层（ZSTD）流。
 *
 * <p>{@link #flush()} 只封<b>完整</b>帧；不足一帧的尾部留待后续。流首一次性写出 PREAMBLE，
 * 兼作"服务端确实在变换"的显式信号（对端 {@link UntransformingInputStream} 据此识别）。
 * 关闭时把剩余完整帧封块、并把不足一帧的尾字节封成原样尾块，保证逆向逐字节还原。
 *
 * <p>注意：跨 block 的列匹配收益依赖底层 ZSTD 的<b>连续帧</b>（{@code setCloseFrameOnFlush(false)}），
 * 匹配历史跨 flush/跨 block 保留——勿改为每次 flush 关帧，否则静默废掉收益。
 */
public final class TransformingOutputStream extends OutputStream {
    private final OutputStream out;
    private final PacketFramer framer = new PacketFramer();
    private final int version;
    private final byte[] one = new byte[1];
    private boolean preambleWritten = false;
    private boolean closed = false;

    public TransformingOutputStream(OutputStream out, int version) {
        this.out = out;
        this.version = version;
    }

    @Override
    public void write(int b) throws IOException {
        one[0] = (byte) b;
        write(one, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("zstdnet transform: write after close");
        }
        framer.append(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        PacketFramer.Batch batch = framer.peelComplete();
        if (batch != null) {
            ensurePreamble();
            StreamTransformCodec.encodeLayerABlock(batch, out);
            framer.consume(batch);
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (OutputStream underlying = out) {
            PacketFramer.Batch batch = framer.peelComplete();
            if (batch != null) {
                ensurePreamble();
                StreamTransformCodec.encodeLayerABlock(batch, underlying);
                framer.consume(batch);
            }
            if (framer.hasRemainder()) {
                ensurePreamble();
                StreamTransformCodec.encodeRawTailBlock(framer.drainRemainder(), underlying);
            }
            underlying.flush();
        }
    }

    private void ensurePreamble() throws IOException {
        if (preambleWritten) {
            return;
        }
        preambleWritten = true;
        out.write(TransformFormat.PREAMBLE_MAGIC);
        out.write(version);
    }
}
