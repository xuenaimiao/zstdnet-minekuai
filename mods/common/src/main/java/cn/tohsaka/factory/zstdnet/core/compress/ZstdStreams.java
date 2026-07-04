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

package cn.tohsaka.factory.zstdnet.core.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;

/**
 * 集中封装 zstd-jni 流的构造与帧探测，使 LDM / 字典 等高级参数只在一处落地，便于测试与复用。
 * <p>
 * 所有方法在 {@link CompressionOptions#none()} 下退化为与历史一致的纯流式压缩（持续帧、无附加参数）。
 */
public final class ZstdStreams {
    /**
     * 探测 ZSTD 帧头里的 dict id 所需读取的字节数。
     * 帧头中 dict id 字段最远落在 magic(4)+FHD(1)+window(1)+dictId(4)=10 字节内，留余量取 18。
     */
    public static final int FRAME_DICT_ID_PEEK = 18;

    /** ZSTD 标准帧魔数在 wire（小端）下的前 4 字节：0x28 0xB5 0x2F 0xFD。 */
    private static final byte[] FRAME_MAGIC = {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD};

    private ZstdStreams() {
    }

    /**
     * 判断 {@code buf} 的前 {@code len} 字节是否以 ZSTD 标准帧魔数开头（需至少 4 个字节才可能为 true）。
     * <p>用于在「整条下行无有效产出」时区分「对端发了真 zstd 帧（后端崩在握手中）」与「对端根本不说 ZSTD」。
     */
    public static boolean startsWithFrameMagic(byte[] buf, int len) {
        if (buf == null || len < FRAME_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < FRAME_MAGIC.length; i++) {
            if (buf[i] != FRAME_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造压缩流：持续帧（{@code setCloseFrameOnFlush(false)}）+ 可选 LDM + 可选字典。
     *
     * @param out           调用方已包好的（通常是计数）输出流
     * @param level         ZSTD 压缩等级
     * @param options       附加参数；null 等价于 {@link CompressionOptions#none()}
     * @param useDictionary 本方向是否使用字典（由上层协商结果决定）
     */
    public static OutputStream newCompressor(OutputStream out, int level, CompressionOptions options, boolean useDictionary) throws IOException {
        boolean longMatching = options != null && options.longDistanceMatching();
        int windowLog = longMatching ? options.effectiveWindowLog() : 0;
        byte[] dict = (useDictionary && options != null && options.hasDictionary()) ? options.dictionary() : null;
        return ZstdCodecs.newCompressor(out, level, longMatching, windowLog, dict);
    }

    /**
     * 构造解压流：可选放开大窗口上限 + 可选字典。
     *
     * @param in         调用方已包好的（通常是计数）输入流
     * @param options    附加参数；仅当本端 windowLog &gt; 27 才会抬高解码窗口上限
     * @param dictionary 解压所需字典；null 表示无字典（与无字典帧匹配）
     */
    public static InputStream newDecompressor(InputStream in, CompressionOptions options, byte[] dictionary) throws IOException {
        int windowLogMax = (options != null && options.decompressWindowLogMax() > CompressionOptions.DEFAULT_DECOMPRESS_WINDOW_LOG_MAX)
            ? options.decompressWindowLogMax()
            : 0;
        return ZstdCodecs.newDecompressor(in, windowLogMax, dictionary);
    }

    /**
     * 探测 {@code in} 中下一个 ZSTD 帧头里的 dict id，并把读到的字节全部退回（不消费）。
     *
     * @return 帧使用的字典 id；0 表示无字典 / 读取不足 / 非 zstd 帧
     */
    public static long peekFrameDictId(PushbackInputStream in) throws IOException {
        byte[] head = new byte[FRAME_DICT_ID_PEEK];
        int read = 0;
        while (read < head.length) {
            int n = in.read(head, read, head.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        if (read == 0) {
            return 0L;
        }
        long dictId;
        try {
            dictId = ZstdCodecs.getDictIdFromFrame(read == head.length ? head : Arrays.copyOf(head, read));
        } catch (Throwable ignored) {
            // getDictIdFromFrame 在字节不足/非 zstd 帧时已返回 0；此处仅兜底原生异常。
            dictId = 0L;
        }
        in.unread(head, 0, read);
        return dictId;
    }
}
