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

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 变换 block 的纯编解码（无 IO、无状态）。Layer A：把一批完整 MC 帧拆成
 * L_SECTION（长度前缀逐字节原样）+ P_SECTION（载荷拼接）。逆向严格还原原始字节流。
 *
 * <p>{@link #decodeBlock} 为增量友好：缓冲不足一个完整 block 时返回 {@link #NEED_MORE}，
 * 调用方补字节后重试；遇到非法类型/越界/损坏 varint 抛 {@link IOException}（fail-closed）。
 */
public final class StreamTransformCodec {
    /** {@link #decodeBlock} 的返回值：当前缓冲不足以解出一个完整 block。 */
    public static final int NEED_MORE = -1;

    private StreamTransformCodec() {
    }

    // ---- 编码 ----

    /** 写出一个 Layer A block（不含流首 PREAMBLE，由调用方负责一次性写前导）。 */
    public static void encodeLayerABlock(PacketFramer.Batch batch, OutputStream sink) throws IOException {
        ByteArrayOutputStream lSection = new ByteArrayOutputStream();
        ByteArrayOutputStream pSection = new ByteArrayOutputStream();

        int cursor = batch.start;
        for (int i = 0; i < batch.count; i++) {
            VarIntRead prefix = VarIntCodec.read(batch.buffer, cursor, batch.end);
            // batch 已由 PacketFramer 保证是完整帧，这里必非 null。
            int prefixStart = cursor;
            int prefixEnd = prefix.next();
            int payloadLen = prefix.value();
            lSection.write(batch.buffer, prefixStart, prefixEnd - prefixStart);
            pSection.write(batch.buffer, prefixEnd, payloadLen);
            cursor = prefixEnd + payloadLen;
        }

        byte[] lBytes = lSection.toByteArray();
        sink.write(TransformFormat.BLOCK_LAYER_A);
        sink.write(VarIntCodec.encode(batch.count));
        sink.write(VarIntCodec.encode(lBytes.length));
        sink.write(lBytes);
        pSection.writeTo(sink);
    }

    /** 写出一个原样尾块（流尾不足一帧的剩余字节，逆向原样还原）。 */
    public static void encodeRawTailBlock(byte[] tail, OutputStream sink) throws IOException {
        sink.write(TransformFormat.BLOCK_RAW_TAIL);
        sink.write(VarIntCodec.encode(tail.length));
        if (tail.length > 0) {
            sink.write(tail);
        }
    }

    /** 编码端单帧分类的中间结果（仅 encode 用）。 */
    private static final class BFrame {
        int prefixStart, prefixLen;   // 长度前缀字节（原样保留）
        int payloadStart, payloadEnd; // 载荷字节区间
        int cls = TransformFormat.CLASS_RAW;
        // 实体定向头（cls>=1 时有效）：dataLength / packetId / entityId 三个 varint 的字节区间
        int dlStart, dlEnd, pidStart, pidEnd, eidStart, eidEnd;
    }

    /**
     * 写出一个 Layer B block：对 batch 内每帧按 {@code table} 分类去交错。{@code version} 决定可用类别
     * （&ge;{@link TransformFormat#VERSION_B1} 启用 B1 移动 SoA，&ge;{@link TransformFormat#VERSION_B2} 启用
     * B2 实体定向）。若全部帧都落 RAW，则退回更省的 {@link #encodeLayerABlock}。
     *
     * <p><b>分类不影响正确性</b>：每帧均做长度校验，任何不符即 RAW；B1/B2 都是"切片+拼回"的字节精确可逆操作。
     */
    public static void encodeLayerBBlock(PacketFramer.Batch batch, OutputStream sink, int version, EntityPacketTable table)
        throws IOException {
        BFrame[] frames = new BFrame[batch.count];
        List<int[]> layouts = new ArrayList<>();
        boolean anyClassified = false;

        int cursor = batch.start;
        for (int i = 0; i < batch.count; i++) {
            VarIntRead prefix = VarIntCodec.read(batch.buffer, cursor, batch.end);
            // batch 已由 PacketFramer 保证为完整帧，这里必非 null。
            BFrame f = new BFrame();
            f.prefixStart = cursor;
            f.prefixLen = prefix.next() - cursor;
            f.payloadStart = prefix.next();
            f.payloadEnd = prefix.next() + prefix.value();
            classify(f, batch.buffer, version, table, layouts);
            if (f.cls != TransformFormat.CLASS_RAW) {
                anyClassified = true;
            }
            frames[i] = f;
            cursor = f.payloadEnd;
        }

        if (!anyClassified) {
            // 本窗口没有可去交错的实体帧：退回 Layer A，省去类别段/布局表/空列开销。
            encodeLayerABlock(batch, sink);
            return;
        }

        sink.write(TransformFormat.BLOCK_LAYER_B);
        sink.write(VarIntCodec.encode(batch.count));

        // L_SECTION：各帧长度前缀逐字节原样。
        ByteArrayOutputStream lSection = new ByteArrayOutputStream();
        for (BFrame f : frames) {
            lSection.write(batch.buffer, f.prefixStart, f.prefixLen);
        }
        byte[] lBytes = lSection.toByteArray();
        sink.write(VarIntCodec.encode(lBytes.length));
        sink.write(lBytes);

        // 布局表：B1 各布局的字段宽度。
        sink.write(VarIntCodec.encode(layouts.size()));
        for (int[] widths : layouts) {
            sink.write(VarIntCodec.encode(widths.length));
            for (int w : widths) {
                sink.write(VarIntCodec.encode(w));
            }
        }

        // CLASS_SECTION：每帧一字节类别。
        for (BFrame f : frames) {
            sink.write(f.cls);
        }

        // RAW 平面：class-0 帧整个载荷，原序。
        for (BFrame f : frames) {
            if (f.cls == TransformFormat.CLASS_RAW) {
                sink.write(batch.buffer, f.payloadStart, f.payloadEnd - f.payloadStart);
            }
        }
        // 实体定向共享三列：dataLength / packetId / entityId（class>=1 帧，原序）。
        for (BFrame f : frames) {
            if (f.cls >= TransformFormat.CLASS_B2_ENTITY_LEADING) {
                sink.write(batch.buffer, f.dlStart, f.dlEnd - f.dlStart);
            }
        }
        for (BFrame f : frames) {
            if (f.cls >= TransformFormat.CLASS_B2_ENTITY_LEADING) {
                sink.write(batch.buffer, f.pidStart, f.pidEnd - f.pidStart);
            }
        }
        for (BFrame f : frames) {
            if (f.cls >= TransformFormat.CLASS_B2_ENTITY_LEADING) {
                sink.write(batch.buffer, f.eidStart, f.eidEnd - f.eidStart);
            }
        }
        // B2 REST 平面：class-1 帧 entityId 之后的剩余字节，原序。
        for (BFrame f : frames) {
            if (f.cls == TransformFormat.CLASS_B2_ENTITY_LEADING) {
                sink.write(batch.buffer, f.eidEnd, f.payloadEnd - f.eidEnd);
            }
        }
        // B1 字段列：按布局 id 顺序、每布局按字段顺序，把同字段跨帧拼成连续列。
        for (int l = 0; l < layouts.size(); l++) {
            int[] widths = layouts.get(l);
            int wantCls = TransformFormat.CLASS_B1_BASE + l;
            for (int j = 0; j < widths.length; j++) {
                int fieldOffset = sumWidths(widths, j);
                for (BFrame f : frames) {
                    if (f.cls == wantCls) {
                        sink.write(batch.buffer, f.eidEnd + fieldOffset, widths[j]);
                    }
                }
            }
        }
    }

    /** 对单帧做分类，命中时填好头部三段区间并设置 {@code cls}；否则保持 {@link TransformFormat#CLASS_RAW}。 */
    private static void classify(BFrame f, byte[] buf, int version, EntityPacketTable table, List<int[]> layouts) {
        if (version < TransformFormat.VERSION_B1 || table == null) {
            return;
        }
        // 读 dataLength（play 阶段恒为 0；非 0 多为登录阶段/压缩载荷 → 不归类）。
        VarIntRead dl = VarIntCodec.read(buf, f.payloadStart, f.payloadEnd);
        if (dl == null || dl.value() != 0) {
            return;
        }
        VarIntRead pid = VarIntCodec.read(buf, dl.next(), f.payloadEnd);
        if (pid == null) {
            return;
        }
        int packetId = pid.value();
        int[] layout = table.moveLayout(packetId);
        boolean entityLeading = table.isEntityLeading(packetId);
        if (layout == null && !entityLeading) {
            return;
        }
        VarIntRead eid = VarIntCodec.read(buf, pid.next(), f.payloadEnd);
        if (eid == null || eid.next() > f.payloadEnd) {
            return;
        }
        int restLen = f.payloadEnd - eid.next();

        int chosen = TransformFormat.CLASS_RAW;
        if (layout != null && version >= TransformFormat.VERSION_B1 && sumWidths(layout, layout.length) == restLen) {
            int layoutId = internLayout(layouts, layout);
            if (layoutId >= 0 && TransformFormat.CLASS_B1_BASE + layoutId <= TransformFormat.MAX_CLASS) {
                chosen = TransformFormat.CLASS_B1_BASE + layoutId;
            }
        }
        if (chosen == TransformFormat.CLASS_RAW && version >= TransformFormat.VERSION_B2 && entityLeading) {
            chosen = TransformFormat.CLASS_B2_ENTITY_LEADING; // restLen>=0 已保证
        }
        if (chosen == TransformFormat.CLASS_RAW) {
            return;
        }
        f.cls = chosen;
        f.dlStart = f.payloadStart;
        f.dlEnd = dl.next();
        f.pidStart = dl.next();
        f.pidEnd = pid.next();
        f.eidStart = pid.next();
        f.eidEnd = eid.next();
    }

    /** 字段宽度前 {@code count} 项之和。 */
    private static int sumWidths(int[] widths, int count) {
        int s = 0;
        for (int j = 0; j < count; j++) {
            s += widths[j];
        }
        return s;
    }

    /** 在已收集布局列表里查找等价布局，找到返回其 id；否则追加并返回新 id（受表上限保护）。 */
    private static int internLayout(List<int[]> layouts, int[] layout) {
        for (int i = 0; i < layouts.size(); i++) {
            if (Arrays.equals(layouts.get(i), layout)) {
                return i;
            }
        }
        if (layouts.size() >= TransformFormat.MAX_LAYOUTS_PER_BLOCK) {
            return -1;
        }
        layouts.add(layout.clone());
        return layouts.size() - 1;
    }

    // ---- 解码 ----

    /**
     * 尝试从 {@code buf[off, end)} 解出一个完整 block，把还原出的原始字节写入 {@code out}。
     *
     * @return 已消费的字节数（&gt;0）；或 {@link #NEED_MORE} 表示缓冲不足、需补更多字节
     * @throws IOException 非法块类型 / 损坏 varint / 越界（流损坏或错位，fail-closed）
     */
    public static int decodeBlock(byte[] buf, int off, int end, ByteArrayOutputStream out) throws IOException {
        if (off >= end) {
            return NEED_MORE;
        }
        int type = buf[off] & 0xFF;
        int p = off + 1;

        if (type == TransformFormat.BLOCK_LAYER_A) {
            return decodeLayerA(buf, off, p, end, out);
        }
        if (type == TransformFormat.BLOCK_LAYER_B) {
            return decodeLayerB(buf, off, p, end, out);
        }
        if (type == TransformFormat.BLOCK_RAW_TAIL) {
            return decodeRawTail(buf, off, p, end, out);
        }
        throw new IOException("zstdnet transform: unknown block type 0x" + Integer.toHexString(type));
    }

    private static int decodeLayerA(byte[] buf, int blockStart, int p, int end, ByteArrayOutputStream out) throws IOException {
        VarIntRead frameCount = readVarintOrNeedMore(buf, p, end);
        if (frameCount == null) {
            return NEED_MORE;
        }
        int count = frameCount.value();
        p = frameCount.next();
        if (count < 0) {
            throw new IOException("zstdnet transform: negative frame count");
        }

        VarIntRead lLen = readVarintOrNeedMore(buf, p, end);
        if (lLen == null) {
            return NEED_MORE;
        }
        int lSectionLen = lLen.value();
        p = lLen.next();
        if (lSectionLen < 0 || lSectionLen > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: L section length out of range: " + lSectionLen);
        }
        if (p + lSectionLen > end) {
            return NEED_MORE; // L 段未到齐
        }
        int lStart = p;
        int lEnd = p + lSectionLen;

        // 第一遍：解析 L 段得到各帧载荷长度，校验恰好消费完且帧数吻合，累计 P 段长度。
        long pSum = 0;
        int lp = lStart;
        for (int i = 0; i < count; i++) {
            VarIntRead r = VarIntCodec.read(buf, lp, lEnd);
            if (r == null) {
                throw new IOException("zstdnet transform: corrupt L section");
            }
            int payloadLen = r.value();
            if (payloadLen < 0 || payloadLen > TransformFormat.MAX_FRAME_LENGTH) {
                throw new IOException("zstdnet transform: payload length out of range: " + payloadLen);
            }
            pSum += payloadLen;
            lp = r.next();
            if (pSum > TransformFormat.MAX_BLOCK_PAYLOAD) {
                throw new IOException("zstdnet transform: block payload too large");
            }
        }
        if (lp != lEnd) {
            throw new IOException("zstdnet transform: L section frame count mismatch");
        }

        long pEnd = (long) lEnd + pSum;
        if (pEnd > end) {
            return NEED_MORE; // P 段未到齐
        }

        // 第二遍：逐帧还原 长度前缀(原样) + 载荷。
        lp = lStart;
        int payOff = lEnd;
        for (int i = 0; i < count; i++) {
            VarIntRead r = VarIntCodec.read(buf, lp, lEnd);
            int prefixStart = lp;
            int prefixEnd = r.next();
            int payloadLen = r.value();
            out.write(buf, prefixStart, prefixEnd - prefixStart);
            out.write(buf, payOff, payloadLen);
            lp = prefixEnd;
            payOff += payloadLen;
        }
        return (int) pEnd - blockStart;
    }

    /**
     * 解码 Layer B block，<b>完全不依赖任何 packet 表</b>：所有反交错所需信息（类别段、block 内嵌的 B1 布局表、
     * L_SECTION 长度）都在 block 自身。单次前向遍历定位各段；缓冲不足返回 {@link #NEED_MORE}（不写 {@code out}）；
     * 损坏/越界抛 {@link IOException}（fail-closed）。
     */
    private static int decodeLayerB(byte[] buf, int blockStart, int p, int end, ByteArrayOutputStream out) throws IOException {
        int c = p;

        // 1. 帧数
        VarIntRead cntR = readVarintOrNeedMore(buf, c, end);
        if (cntR == null) {
            return NEED_MORE;
        }
        int count = cntR.value();
        c = cntR.next();
        if (count < 0 || count > TransformFormat.MAX_FRAMES_PER_BLOCK) {
            throw new IOException("zstdnet transform: frame count out of range: " + count);
        }

        // 2. L_SECTION
        VarIntRead lLenR = readVarintOrNeedMore(buf, c, end);
        if (lLenR == null) {
            return NEED_MORE;
        }
        int lSectionLen = lLenR.value();
        c = lLenR.next();
        if (lSectionLen < 0 || lSectionLen > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: L section length out of range: " + lSectionLen);
        }
        if (c + lSectionLen > end) {
            return NEED_MORE;
        }
        int lStart = c;
        int lEnd = c + lSectionLen;
        c = lEnd;

        int[] prefixStart = new int[count];
        int[] prefixLen = new int[count];
        int[] payloadLen = new int[count];
        int lp = lStart;
        for (int i = 0; i < count; i++) {
            VarIntRead r = VarIntCodec.read(buf, lp, lEnd);
            if (r == null) {
                throw new IOException("zstdnet transform: corrupt L section");
            }
            prefixStart[i] = lp;
            prefixLen[i] = r.next() - lp;
            payloadLen[i] = r.value();
            if (payloadLen[i] < 0 || payloadLen[i] > TransformFormat.MAX_FRAME_LENGTH) {
                throw new IOException("zstdnet transform: payload length out of range: " + payloadLen[i]);
            }
            lp = r.next();
        }
        if (lp != lEnd) {
            throw new IOException("zstdnet transform: L section frame count mismatch");
        }

        // 3. B1 布局表
        VarIntRead layoutCountR = readVarintOrNeedMore(buf, c, end);
        if (layoutCountR == null) {
            return NEED_MORE;
        }
        int layoutCount = layoutCountR.value();
        c = layoutCountR.next();
        if (layoutCount < 0 || layoutCount > TransformFormat.MAX_LAYOUTS_PER_BLOCK) {
            throw new IOException("zstdnet transform: layout count out of range: " + layoutCount);
        }
        int[][] layouts = new int[layoutCount][];
        int[] layoutTotal = new int[layoutCount];
        for (int k = 0; k < layoutCount; k++) {
            VarIntRead fcR = readVarintOrNeedMore(buf, c, end);
            if (fcR == null) {
                return NEED_MORE;
            }
            int fieldCount = fcR.value();
            c = fcR.next();
            if (fieldCount < 0 || fieldCount > TransformFormat.MAX_FIELDS_PER_LAYOUT) {
                throw new IOException("zstdnet transform: field count out of range: " + fieldCount);
            }
            int[] widths = new int[fieldCount];
            int total = 0;
            for (int j = 0; j < fieldCount; j++) {
                VarIntRead wR = readVarintOrNeedMore(buf, c, end);
                if (wR == null) {
                    return NEED_MORE;
                }
                int w = wR.value();
                c = wR.next();
                if (w < 0 || w > TransformFormat.MAX_FRAME_LENGTH) {
                    throw new IOException("zstdnet transform: field width out of range: " + w);
                }
                widths[j] = w;
                total += w;
                if (total < 0 || total > TransformFormat.MAX_FRAME_LENGTH) {
                    throw new IOException("zstdnet transform: layout total too large");
                }
            }
            layouts[k] = widths;
            layoutTotal[k] = total;
        }

        // 4. CLASS_SECTION
        if (c + count > end) {
            return NEED_MORE;
        }
        int classStart = c;
        c += count;
        int[] cls = new int[count];
        int nEl = 0;
        int[] layoutFrameCount = new int[layoutCount];
        for (int i = 0; i < count; i++) {
            int v = buf[classStart + i] & 0xFF;
            if (v != TransformFormat.CLASS_RAW
                && v != TransformFormat.CLASS_B2_ENTITY_LEADING
                && !(v >= TransformFormat.CLASS_B1_BASE && v - TransformFormat.CLASS_B1_BASE < layoutCount)) {
                throw new IOException("zstdnet transform: bad class byte " + v);
            }
            cls[i] = v;
            if (v >= TransformFormat.CLASS_B2_ENTITY_LEADING) {
                nEl++;
            }
            if (v >= TransformFormat.CLASS_B1_BASE) {
                layoutFrameCount[v - TransformFormat.CLASS_B1_BASE]++;
            }
        }

        // 5. RAW 平面
        long rawSize = 0;
        for (int i = 0; i < count; i++) {
            if (cls[i] == TransformFormat.CLASS_RAW) {
                rawSize += payloadLen[i];
            }
        }
        if (rawSize > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: raw plane too large");
        }
        if (c + rawSize > end) {
            return NEED_MORE;
        }
        int rawStart = c;
        c += (int) rawSize;

        // 6. 实体定向三列（每列 nEl 个自描述 varint，顺序 = class>=1 帧的原序）
        int[] dlStart = new int[nEl];
        int[] dlLen = new int[nEl];
        c = readVarintColumn(buf, c, end, nEl, dlStart, dlLen);
        if (c == NEED_MORE) {
            return NEED_MORE;
        }
        int[] pidStart = new int[nEl];
        int[] pidLen = new int[nEl];
        c = readVarintColumn(buf, c, end, nEl, pidStart, pidLen);
        if (c == NEED_MORE) {
            return NEED_MORE;
        }
        int[] eidStart = new int[nEl];
        int[] eidLen = new int[nEl];
        c = readVarintColumn(buf, c, end, nEl, eidStart, eidLen);
        if (c == NEED_MORE) {
            return NEED_MORE;
        }

        // 帧 -> el 序号映射
        int[] elIndexOf = new int[count];
        int e = 0;
        for (int i = 0; i < count; i++) {
            elIndexOf[i] = cls[i] >= TransformFormat.CLASS_B2_ENTITY_LEADING ? e++ : -1;
        }

        // 7. B2 REST 平面
        long b2Size = 0;
        for (int i = 0; i < count; i++) {
            if (cls[i] == TransformFormat.CLASS_B2_ENTITY_LEADING) {
                int el = elIndexOf[i];
                int headLen = dlLen[el] + pidLen[el] + eidLen[el];
                int restLen = payloadLen[i] - headLen;
                if (restLen < 0) {
                    throw new IOException("zstdnet transform: B2 head exceeds payload");
                }
                b2Size += restLen;
            }
        }
        if (b2Size > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: B2 rest plane too large");
        }
        if (c + b2Size > end) {
            return NEED_MORE;
        }
        int b2Start = c;
        c += (int) b2Size;

        // 8. B1 字段列：列大小 = 该布局帧数 * 字段宽度（可由 CLASS+布局表算出）
        int[][] colStart = new int[layoutCount][];
        for (int k = 0; k < layoutCount; k++) {
            colStart[k] = new int[layouts[k].length];
            for (int j = 0; j < layouts[k].length; j++) {
                long colSize = (long) layoutFrameCount[k] * layouts[k][j];
                if (colSize > TransformFormat.MAX_BLOCK_PAYLOAD) {
                    throw new IOException("zstdnet transform: B1 column too large");
                }
                if (c + colSize > end) {
                    return NEED_MORE;
                }
                colStart[k][j] = c;
                c += (int) colSize;
            }
        }

        // 9. 重组：按原帧序还原 长度前缀 + 载荷
        int rawCur = rawStart;
        int b2Cur = b2Start;
        int[][] colCur = new int[layoutCount][];
        for (int k = 0; k < layoutCount; k++) {
            colCur[k] = colStart[k].clone();
        }
        int elCur = 0;
        for (int i = 0; i < count; i++) {
            out.write(buf, prefixStart[i], prefixLen[i]);
            if (cls[i] == TransformFormat.CLASS_RAW) {
                out.write(buf, rawCur, payloadLen[i]);
                rawCur += payloadLen[i];
                continue;
            }
            int el = elCur++;
            out.write(buf, dlStart[el], dlLen[el]);
            out.write(buf, pidStart[el], pidLen[el]);
            out.write(buf, eidStart[el], eidLen[el]);
            int headLen = dlLen[el] + pidLen[el] + eidLen[el];
            int restLen = payloadLen[i] - headLen;
            if (cls[i] == TransformFormat.CLASS_B2_ENTITY_LEADING) {
                out.write(buf, b2Cur, restLen);
                b2Cur += restLen;
            } else {
                int k = cls[i] - TransformFormat.CLASS_B1_BASE;
                if (layoutTotal[k] != restLen) {
                    throw new IOException("zstdnet transform: B1 layout length mismatch");
                }
                int[] widths = layouts[k];
                for (int j = 0; j < widths.length; j++) {
                    out.write(buf, colCur[k][j], widths[j]);
                    colCur[k][j] += widths[j];
                }
            }
        }
        return c - blockStart;
    }

    /**
     * 从 {@code buf[c..end)} 顺序读 {@code n} 个自描述 varint，把每个的字节起点/字节长写入 {@code outStart}/{@code outLen}，
     * 返回读完后的游标；缓冲不足返回 {@link #NEED_MORE}。
     */
    private static int readVarintColumn(byte[] buf, int c, int end, int n, int[] outStart, int[] outLen) throws IOException {
        for (int i = 0; i < n; i++) {
            VarIntRead r = readVarintOrNeedMore(buf, c, end);
            if (r == null) {
                return NEED_MORE;
            }
            outStart[i] = c;
            outLen[i] = r.next() - c;
            c = r.next();
        }
        return c;
    }

    private static int decodeRawTail(byte[] buf, int blockStart, int p, int end, ByteArrayOutputStream out) throws IOException {
        VarIntRead tailLen = readVarintOrNeedMore(buf, p, end);
        if (tailLen == null) {
            return NEED_MORE;
        }
        int len = tailLen.value();
        p = tailLen.next();
        if (len < 0 || len > TransformFormat.MAX_BLOCK_PAYLOAD) {
            throw new IOException("zstdnet transform: raw tail length out of range: " + len);
        }
        if (p + len > end) {
            return NEED_MORE;
        }
        out.write(buf, p, len);
        return (p + len) - blockStart;
    }

    /**
     * 读 varint：成功返回；字节不足返回 {@code null}（需补更多）；
     * 已有 ≥5 字节仍解不出则视为损坏抛异常（合法 varint 至多 5 字节）。
     */
    private static VarIntRead readVarintOrNeedMore(byte[] buf, int off, int end) throws IOException {
        VarIntRead r = VarIntCodec.read(buf, off, end);
        if (r != null) {
            return r;
        }
        if (end - off >= 5) {
            throw new IOException("zstdnet transform: corrupt varint");
        }
        return null;
    }
}
