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
    private final EntityPacketTable table;
    private final boolean useLayerB;
    private final byte[] one = new byte[1];
    private boolean preambleWritten = false;
    private boolean closed = false;

    /** 仅 Layer A（无 packet 表）。 */
    public TransformingOutputStream(OutputStream out, int version) {
        this(out, version, null);
    }

    /**
     * @param version 协商生效的变换版本（写入 PREAMBLE）；&ge;{@link TransformFormat#VERSION_B1} 且
     *                {@code table} 非空时启用 Layer B 按帧去交错，否则仅 Layer A。
     * @param table   编码端实体包表（按 MC 协议版本解析；可为 {@code null} → 退化 Layer A）。解码端不需要。
     */
    public TransformingOutputStream(OutputStream out, int version, EntityPacketTable table) {
        this.out = out;
        this.version = version;
        this.table = table;
        this.useLayerB = version >= TransformFormat.VERSION_B1 && table != null && !table.isEmpty();
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
            encodeBatch(batch);
            framer.consume(batch);
        }
        out.flush();
    }

    private void encodeBatch(PacketFramer.Batch batch) throws IOException {
        if (useLayerB) {
            StreamTransformCodec.encodeLayerBBlock(batch, out, version, table);
        } else {
            StreamTransformCodec.encodeLayerABlock(batch, out);
        }
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
                if (useLayerB) {
                    StreamTransformCodec.encodeLayerBBlock(batch, underlying, version, table);
                } else {
                    StreamTransformCodec.encodeLayerABlock(batch, underlying);
                }
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
