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

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;

import java.io.IOException;
import java.util.Arrays;

/**
 * 有状态的增量 Minecraft 帧剥离器。后端 socket 的 16KB 读边界不会对齐 MC 帧边界，
 * 故把流入字节缓冲起来、贪婪剥出尽可能多的<b>完整</b> {@code [VarInt 长度][载荷]} 帧，
 * 并保留尾部不足一帧的剩余字节等待后续字节。纯类、无 IO、可单测。
 *
 * <p>用法：{@link #append} 喂入原始字节 → {@link #peelComplete()} 取完整帧视图 → 编码后
 * {@link #consume} 推进 → 流结束时 {@link #drainRemainder()} 取走剩余字节。
 */
public final class PacketFramer {
    private static final int INITIAL_CAPACITY = 8 * 1024;

    private byte[] buf = new byte[INITIAL_CAPACITY];
    private int start = 0; // 未消费数据起点
    private int limit = 0; // 有效数据终点（[start, limit) 为待处理）
    private final int maxFrameLength;

    public PacketFramer() {
        this(TransformFormat.MAX_FRAME_LENGTH);
    }

    public PacketFramer(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    /** 追加原始字节到缓冲。 */
    public void append(byte[] data, int off, int len) {
        if (len <= 0) {
            return;
        }
        ensureWritable(len);
        System.arraycopy(data, off, buf, limit, len);
        limit += len;
    }

    /**
     * 描述当前缓冲里一段连续的完整帧。{@code buffer[start, end)} 恰好覆盖 {@code count} 个完整帧。
     * 视图直接引用内部缓冲，调用方应在下一次 {@link #append}/{@link #consume} 前用完。
     */
    public static final class Batch {
        public final byte[] buffer;
        public final int start;
        public final int end;
        public final int count;

        Batch(byte[] buffer, int start, int end, int count) {
            this.buffer = buffer;
            this.start = start;
            this.end = end;
            this.count = count;
        }
    }

    /**
     * 扫描并返回当前缓冲里所有完整帧的视图；没有完整帧时返回 {@code null}。
     * 不推进读指针——编码完成后需调用 {@link #consume}。
     *
     * @throws IOException 长度前缀超过 5 字节 varint 或帧长超过上限（流损坏/错位，fail-closed）
     */
    public Batch peelComplete() throws IOException {
        int scan = start;
        int count = 0;
        while (scan < limit) {
            VarIntRead lenPrefix = VarIntCodec.read(buf, scan, limit);
            if (lenPrefix == null) {
                // varint 不完整：若可读字节已 ≥5 仍解不出，说明是非法长度前缀（错位/损坏）。
                if (limit - scan >= 5) {
                    throw new IOException("zstdnet transform: malformed packet length prefix");
                }
                break; // 长度前缀尚未到齐，等更多字节
            }
            int payloadLen = lenPrefix.value();
            if (payloadLen < 0 || payloadLen > maxFrameLength) {
                throw new IOException("zstdnet transform: frame length out of range: " + payloadLen);
            }
            int frameEnd = lenPrefix.next() + payloadLen;
            if (frameEnd > limit) {
                break; // 载荷未到齐
            }
            scan = frameEnd;
            count++;
        }
        if (count == 0) {
            return null;
        }
        return new Batch(buf, start, scan, count);
    }

    /** 推进读指针越过已编码的批次，并压实缓冲。 */
    public void consume(Batch batch) {
        if (batch.buffer != buf) {
            throw new IllegalStateException("zstdnet transform: stale frame batch");
        }
        start = batch.end;
        compact();
    }

    /** 是否还有不足一帧的剩余字节。 */
    public boolean hasRemainder() {
        return start < limit;
    }

    /** 取走全部剩余字节（流结束时调用）。 */
    public byte[] drainRemainder() {
        byte[] out = Arrays.copyOfRange(buf, start, limit);
        start = limit;
        compact();
        return out;
    }

    private void ensureWritable(int extra) {
        if (limit + extra <= buf.length) {
            return;
        }
        // 先尝试压实回收已消费空间
        compact();
        if (limit + extra <= buf.length) {
            return;
        }
        int needed = limit + extra;
        int newCap = buf.length;
        while (newCap < needed) {
            newCap = newCap << 1;
            if (newCap < 0) { // 溢出保护
                newCap = needed;
                break;
            }
        }
        buf = Arrays.copyOf(buf, newCap);
    }

    private void compact() {
        if (start == 0) {
            return;
        }
        int len = limit - start;
        if (len > 0) {
            System.arraycopy(buf, start, buf, 0, len);
        }
        start = 0;
        limit = len;
    }
}
