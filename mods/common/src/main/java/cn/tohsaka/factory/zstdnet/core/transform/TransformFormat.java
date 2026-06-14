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

/**
 * 实体包流变换的线路常量（契约单一真源）。变换层位于 ZSTD <b>之前</b>：把 Minecraft 的
 * {@code [VarInt 长度][载荷]} 帧流去交错重排成自描述 block，使跨 tick 的同字段变成 ZSTD 可匹配的连续段。
 *
 * <p>流结构（变换开启时）：
 * <pre>
 *   PREAMBLE(5):  'Z' 'N' 'T' 'X' VERSION      —— 流首一次性出现，兼作"服务端确实在变换"的显式信号
 *   BLOCK*                                      —— 之后是若干 block，直到 EOF
 * </pre>
 *
 * <p>每个 block：
 * <pre>
 *   BLOCK_LAYER_A(0x01):  FRAME_COUNT(varint) L_SECTION_LEN(varint) L_SECTION P_SECTION
 *   BLOCK_RAW_TAIL(0x7F):  TAIL_LEN(varint) TAIL          —— 流尾不足一帧的剩余字节，原样还原
 * </pre>
 *
 * <p>L_SECTION 为各帧长度前缀的<b>逐字节原样</b>拼接（不重编码 varint，规避最小编码假设）；
 * P_SECTION 为各帧载荷按原序拼接。逆变换按 L_SECTION 切回 P_SECTION，逐帧还原 {@code 长度前缀+载荷}，
 * 顺序天然保持，与原始字节流逐字节一致。
 */
public final class TransformFormat {
    /** 流首 4 字节魔数：'Z' 'N' 'T' 'X'。其后紧跟 1 字节版本号，构成 5 字节前导。 */
    public static final byte[] PREAMBLE_MAGIC = {0x5A, 0x4E, 0x54, 0x58};
    /** 前导总长 = 魔数(4) + 版本(1)。 */
    public static final int PREAMBLE_LENGTH = PREAMBLE_MAGIC.length + 1;

    /** 变换格式版本：仅 Layer A（版本无关的长度/载荷拆分）。 */
    public static final int VERSION_LAYER_A = 1;
    /** 变换格式版本：A + B1（实体移动家族定长字段 SoA）。 */
    public static final int VERSION_B1 = 2;
    /** 变换格式版本：A + B1 + B2（生物特有包 entityId 抽取 + 按类型分组）。 */
    public static final int VERSION_B2 = 3;
    /** 当前实现支持的最高版本（随阶段推进抬升）。 */
    public static final int MAX_SUPPORTED_VERSION = VERSION_LAYER_A;

    /** block 类型：Layer A 去交错块。 */
    public static final int BLOCK_LAYER_A = 0x01;
    /** block 类型：原样尾块（流尾不足一帧的剩余字节）。 */
    public static final int BLOCK_RAW_TAIL = 0x7F;

    /**
     * 单帧长度安全上限（16 MiB）。Minecraft 帧长前缀至多 5 字节 varint；正常帧远小于此。
     * 超过即视为流损坏 / 错位，fail-closed 中止，绝不无限缓冲。
     */
    public static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    /**
     * 单个 block 载荷总量安全上限（64 MiB）。解码遇到超过此值的声明长度即判定为流损坏并 fail-closed，
     * 避免对损坏的超大声明长度无限缓冲（内存 DoS 防护）。正常 flush 窗口的 block 远小于此。
     */
    public static final int MAX_BLOCK_PAYLOAD = 64 * 1024 * 1024;

    private TransformFormat() {
    }
}
