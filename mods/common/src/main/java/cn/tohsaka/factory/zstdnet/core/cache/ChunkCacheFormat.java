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

package cn.tohsaka.factory.zstdnet.core.cache;

import cn.tohsaka.factory.zstdnet.core.transform.TransformFormat;

/**
 * 区块引用缓存（CRC, Chunk Reference Cache）的线路常量（契约单一真源）。CRC 层位于 ZSTD <b>之前</b>，
 * 把“服务端→客户端”的 Minecraft 帧流重写成自描述 block：把客户端<b>已证明持有</b>的相同区块包换成 8 字节
 * REF 令牌（客户端重放本地缓存的原始字节），其余帧原样透传。
 *
 * <p>流结构（CRC 启用时）：
 * <pre>
 *   PREAMBLE(5):  'Z' 'N' 'C' 'R' VERSION       —— 流首一次性出现，兼作“服务端确实在 CRC”的显式信号
 *   BLOCK*                                        —— 之后是若干 block，直到 EOF
 * </pre>
 *
 * <p>每个 block：
 * <pre>
 *   BLOCK_PASSTHROUGH(0x01):  LEN(varint) RAW_BYTES[LEN]         —— 一段不可缓存帧的逐字节原样透传
 *   BLOCK_FULL(0x02):         HASH(8) LEN(varint) FRAME[LEN]      —— 整发的可缓存帧；客户端缓存 HASH→FRAME（并落盘持久化）
 *   BLOCK_REF(0x03):          HASH(8)                             —— 客户端重放 HASH 对应的<b>会话内</b>缓存字节
 *   BLOCK_WARM_REF(0x04):     HASH128(16)                         —— 客户端重放 HASH128 对应的<b>跨会话</b>磁盘缓存字节（v2）
 *   BLOCK_PATCH(0x05):        BASE(8) NEW(8) DLEN(varint) DELTA   —— 相对会话内基线 BASE 的字节级增量；重建后须 hash==NEW（v3）
 * </pre>
 *
 * <p>会话内 HASH 为该帧整字节（{@code [VarInt 长度][载荷]}）的 64 位内容哈希（大端 8 字节）。WARM_REF 的
 * HASH128 是同一帧的 128 位内容哈希（大端 16 字节）：跨会话时服务端手里没有字节、无法比对，只能信任“同哈希 ⟹
 * 同字节”，故须 128 位令碰撞可忽略（与 BO 用 SHA-256 同理）。RAW_BYTES / FRAME 均为原始 MC 帧字节，逆向后逐字节一致。
 * <b>解码端完全自描述、不依赖任何 packet-id 表</b>（表只在编码端用于决定哪些帧可缓存；表错只会少压、绝不损坏）。
 *
 * <p><b>跨会话 manifest（v2，c2s）：</b>客户端在握手后、login-start 前，于 c2s 流插入一个 {@code ZNCM} 帧，
 * 列出它磁盘缓存里持有的 HASH128 集合。服务端据此知道“客户端已持有哪些区块”，对这些区块发 WARM_REF。
 * 服务端读 manifest 时<b>自纠错</b>：若该帧不以 {@code ZNCM} 起头（版本错配 / 客户端没发），则按普通包转发后端。
 *
 * <p><b>正确性不变量：</b>服务端只对“自己已以 FULL 发过且未被淘汰”的 HASH 发 REF；编码端与客户端缓存用
 * 同一套 LRU + 字节预算，淘汰同步 → 会话内 REF 不可能 miss。REF 候选另做整字节比对（规避 64 位碰撞）。
 * 任何非法 block / 截断 / 缺失基线 → fail-closed 抛 {@link java.io.IOException} 中止连接（原版自动重连），绝不发错包。
 *
 * <p><b>PATCH 正确性（v3）：</b>PATCH 基线只取<b>会话内</b> LRU 里的帧（编码端 {@code peek} 命中 ⟺ 解码端 lock-step
 * 同状态下 {@code peek} 必命中，故不会缺基线），<b>绝不</b>以 WARM_REF 帧为基线（那不进会话内 LRU）。基线在两端都用
 * {@code peek}（不触碰 LRU）→ 增量重建后两端都 {@code put(NEW, 结果)}（与 FULL 同样进 LRU）→ lock-step 不变。
 * 解码端结构无关：仅按 {@code DELTA} 的 copy/insert 字节算子重建，再校验 {@code content128(结果).hi()==NEW}，
 * 不符即 fail-closed。编码端按协议解析（如有）只用于<b>挑更小的增量</b>，解析缺失/出错时退回通用字节 diff 或 FULL，
 * 均不影响正确性。版本协商保证：服务端写出的生效版本 = min(客户端 advertise, 服务端 MAX)，故只发对端版本能解的 block。
 */
public final class ChunkCacheFormat {
    /** 流首 4 字节魔数：'Z' 'N' 'C' 'R'。其后紧跟 1 字节版本号，构成 5 字节前导。 */
    public static final byte[] PREAMBLE_MAGIC = {0x5A, 0x4E, 0x43, 0x52};
    /** 前导总长 = 魔数(4) + 版本(1)。 */
    public static final int PREAMBLE_LENGTH = PREAMBLE_MAGIC.length + 1;

    /** CRC 格式版本：会话内 REF 去重（PASSTHROUGH/FULL/REF）。 */
    public static final int VERSION_REF = 1;
    /** CRC 格式版本：在 v1 之上增加跨会话持久化（ZNCM manifest + WARM_REF）。 */
    public static final int VERSION_MANIFEST = 2;
    /** CRC 格式版本：在 v2 之上增加结构无关的字节级 PATCH（相对会话内已持有基线发增量；Phase 4）。 */
    public static final int VERSION_PATCH = 3;
    /** 当前实现支持的最高版本（随阶段推进抬升）。 */
    public static final int MAX_SUPPORTED_VERSION = VERSION_PATCH;

    /** block 类型：不可缓存帧的逐字节透传段。 */
    public static final int BLOCK_PASSTHROUGH = 0x01;
    /** block 类型：整发并缓存的可缓存帧。 */
    public static final int BLOCK_FULL = 0x02;
    /** block 类型：引用客户端会话内已持有的缓存帧（8 字节 hash）。 */
    public static final int BLOCK_REF = 0x03;
    /** block 类型（v2）：引用客户端<b>跨会话</b>磁盘缓存的帧（16 字节 hash128）。 */
    public static final int BLOCK_WARM_REF = 0x04;
    /** block 类型（v3）：相对客户端<b>会话内</b>已持有基线（8 字节 baseHash）的字节级增量。 */
    public static final int BLOCK_PATCH = 0x05;

    /** REF 令牌 / FULL 头里的会话内内容哈希字节数（大端 long）。 */
    public static final int HASH_BYTES = 8;
    /** WARM_REF 令牌 / manifest 条目 / 磁盘键的 128 位内容哈希字节数（大端 16 字节）。 */
    public static final int HASH128_BYTES = 16;

    // ---- 跨会话 manifest（v2，c2s 帧）----

    /** manifest 帧的魔数：'Z' 'N' 'C' 'M'（区别于 s2c 的 'Z' 'N' 'C' 'R'）。 */
    public static final byte[] MANIFEST_MAGIC = {0x5A, 0x4E, 0x43, 0x4D};
    /** manifest 格式版本。 */
    public static final int MANIFEST_VERSION = 1;
    /** manifest 条目数硬上限（防御损坏声明 / 内存 DoS）。每条 16 字节 → 帧 ≤ ~1 MiB。 */
    public static final int MAX_MANIFEST_ENTRIES = 1 << 16;
    /** 服务端读取 manifest 帧的字节上限（含魔数/版本/计数开销，留足余量）。 */
    public static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;

    /**
     * 每条连接的缓存字节预算（编码端与解码端<b>必须一致</b>，以保证 LRU 同步淘汰 → 会话内 REF 不 miss）。
     * Phase 1 取固定协议常量（两端各自编译进同一值，天然一致，无需协商）：16 MiB ≈ 容下数十~数百个区块的工作集。
     * 服务端多人场景的可配置 + 协商预算留待 Phase 2（经流首 header 下发，使客户端精确匹配服务端选定值）。
     * <p><b>锁步契约：</b>此值连同 {@code LruByteCache.ENTRY_OVERHEAD} 决定逐出点，编/解码两端必须一致。改动其一
     * <b>必须同时抬升 {@link #MAX_SUPPORTED_VERSION}</b>，否则混合 release 的两端逐出不同步 → REF/PATCH 基线 miss → 反复断连。
     */
    public static final long DEFAULT_CACHE_BYTES = 16L * 1024 * 1024;

    /**
     * 客户端跨会话<b>持久化</b>缓存的默认字节预算（磁盘 + 加载进内存的 warm 集合共用一个值）。
     * 与会话内 16 MiB LRU 相互独立。可经客户端配置覆盖；过大只是多占客户端磁盘/内存，不影响正确性。
     */
    public static final long DEFAULT_PERSIST_BYTES = 64L * 1024 * 1024;

    /**
     * AUTO 自适应旁路窗口：连续这么多个“全新”可缓存帧（既非会话内 REF 命中、也非 WARM 命中）后，
     * 编码端暂时把可缓存帧也当 PASSTHROUGH（省去哈希/入缓存开销），并周期性重新探测。0 = 关闭旁路。
     * 纯编码端决策、对解码端零影响（解码端给什么块处理什么块）。对应 BO 的“16 次不划算就放弃”。
     */
    public static final int DEFAULT_AUTO_BYPASS_WINDOW = 16;

    // ---- PATCH（v3）调参（编码端常量；正确性不依赖其取值——错只会少压/多发 FULL，绝不损坏）----

    /**
     * 同一区块坐标允许的连续 PATCH 链长上限：达到后强制一次 FULL 重锚（re-anchor）。
     * 限制累计漂移、并周期性落定一个可跨会话 WARM_REF 的干净 FULL 基线。对应 BO 的“单基线 ≤64 次 patch”。
     */
    public static final int DEFAULT_PATCH_CHAIN_BUDGET = 64;
    /**
     * 只有当增量字节 ≤ 新帧字节 × 此百分比时才发 PATCH（否则 FULL 更划算）。整数百分比避免浮点。
     */
    public static final int DEFAULT_PATCH_MAX_RATIO_PERCENT = 60;
    /** PATCH 解码端：编码所用的最小匹配长度（短于此的 COPY 不划算）。仅供 {@code DeltaCodec} 共享。 */
    public static final int PATCH_MIN_MATCH = 8;

    /** 单帧长度安全上限（复用变换层的 16 MiB）。超过即视为流损坏，fail-closed。 */
    public static final int MAX_FRAME_LENGTH = TransformFormat.MAX_FRAME_LENGTH;
    /** 单个 block 载荷安全上限（64 MiB），防御损坏声明导致的超大缓冲（内存 DoS 防护）。 */
    public static final int MAX_BLOCK_PAYLOAD = TransformFormat.MAX_BLOCK_PAYLOAD;

    private ChunkCacheFormat() {
    }
}
