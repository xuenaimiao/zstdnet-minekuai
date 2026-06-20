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

/**
 * PATCH 的<b>编码端</b>结构感知增量 SPI（可选优化点；缺省不挂任何实现）。
 *
 * <p><b>架构约束（净室复刻 BO 的关键风险隔离）：</b>PATCH 的<b>解码端永远版本无关</b>——它只跑
 * {@link DeltaCodec#apply} 的通用 copy/insert 算子并做哈希校验门，<b>从不</b>知道区块结构。版本脆弱的“按协议解析
 * 调色板 / section / NBT 区间以挑出更小增量”一律只发生在编码端、藏在本接口之后：实现解析的是<b>线字节</b>区间，
 * <b>绝不</b>触碰 {@code net.minecraft.*}（故单一真源 {@code mods/common} 规则成立，无需任何按变体的 Java）。
 *
 * <p>实现产出的仍是 {@link DeltaCodec} 同一套算子格式的 {@code byte[]}（解码端不关心它怎么算出来的）；返回
 * {@code null} 表示“放弃，调用方退回 {@link DeltaCodec#diff} 的通用字节 diff”。因此<b>有没有结构解析器都不影响
 * 正确性，只影响增量大小</b>——这正是把版本脆弱性挡在正确性之外的设计。
 *
 * <p>当前阶段<b>不</b>内置任何按协议的实现（{@link #forProtocol} 恒返回 {@code null} → 一律走通用字节 diff，
 * 已能吃下“同坐标区块小改动”的主要收益）。后续要加结构解析时，只需在此返回对应协议的实现，无需改线协议 / 解码端。
 */
public interface ChunkStructureParser {

    /**
     * 给定同一区块坐标的旧帧 {@code base} 与新帧 {@code target}（均为完整 MC 帧线字节，含外层 VarInt 长度前缀），
     * 返回一个 {@link DeltaCodec} 格式的、结构感知的更紧凑增量；无法/不划算时返回 {@code null}（退回通用字节 diff）。
     */
    byte[] structuralDiff(byte[] base, byte[] target);

    /**
     * 按 Minecraft 协议版本号选择结构解析器；未覆盖 / 当前阶段一律返回 {@code null}（→ 通用字节 diff）。
     */
    static ChunkStructureParser forProtocol(int protocol) {
        return null;
    }
}
