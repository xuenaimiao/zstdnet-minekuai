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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 可缓存包识别表，按 <b>Minecraft 协议版本号</b>键入（握手里的 protocol VarInt）。<b>仅编码端 / 测量端使用</b>——
 * 用于识别“服务端→客户端”哪些帧是值得引用缓存去重的大包。Phase 0 仅全区块包
 * （{@code ClientboundLevelChunkWithLightPacket}，即 “Chunk Data and Update Light”）；后续阶段补
 * block-entity-data / section-blocks-update / light / block-update。
 *
 * <p>与 {@link cn.tohsaka.factory.zstdnet.core.transform.EntityPacketTable} 同样的安全性质：
 * <b>解码端永不使用本表</b>（CRC block 自描述），因此本表错（packetId 不对 / 版本缺失）<b>只影响“按 id 归类”
 * 的统计或压缩率，绝不损坏数据</b>。Phase 0 测量另有一条与 id 无关的“按帧大小”重复统计作为权威交叉校验：
 * 若“按 id”重复字节远小于“按大小”重复字节，多半是本表的 id 不对该协议版本。
 *
 * <p>缺失协议版本（未在 {@link #forProtocol} 覆盖）返回 {@code null}，调用方退化为只用“按大小”统计。
 */
public final class CacheablePacketTable {
    private final Set<Integer> fullChunkIds;

    private CacheablePacketTable(Set<Integer> fullChunkIds) {
        this.fullChunkIds = fullChunkIds;
    }

    /** packetId 是否为全区块包（clientbound PLAY）。 */
    public boolean isFullChunk(int packetId) {
        return fullChunkIds.contains(packetId);
    }

    /** packetId 是否为本阶段任一可缓存包（Phase 0 等价于全区块包）。 */
    public boolean isCacheable(int packetId) {
        return isFullChunk(packetId);
    }

    public boolean isEmpty() {
        return fullChunkIds.isEmpty();
    }

    // ---- 按协议版本的内置表 ----

    private static final Map<Integer, CacheablePacketTable> CACHE = new HashMap<>();

    /**
     * 返回该协议版本的可缓存包表；未覆盖的版本返回 {@code null}。
     *
     * <p>已覆盖：760=1.19.2、763=1.20.1、767=1.21.1（与 {@link cn.tohsaka.factory.zstdnet.core.transform.EntityPacketTable}
     * 覆盖的版本一致）。packetId 为各版本 clientbound PLAY 全区块包编号，经 PrismarineJS minecraft-data /
     * wiki.vg 交叉核对；仅影响“按 id 归类”统计（见类注释的安全性质）。
     */
    public static synchronized CacheablePacketTable forProtocol(int protocol) {
        if (CACHE.containsKey(protocol)) {
            return CACHE.get(protocol);
        }
        CacheablePacketTable table = build(protocol);
        CACHE.put(protocol, table);
        return table;
    }

    private static CacheablePacketTable build(int protocol) {
        switch (protocol) {
            case 760: // Minecraft 1.19.2
                return ofFullChunkIds(0x21);
            case 763: // Minecraft 1.20.1
                return ofFullChunkIds(0x24);
            case 767: // Minecraft 1.21.1
                return ofFullChunkIds(0x27);
            default:
                return null;
        }
    }

    /** 合成表构造（单测 / 后续阶段补包用）。 */
    public static CacheablePacketTable ofFullChunkIds(int... fullChunkIds) {
        Set<Integer> set = new HashSet<>();
        for (int id : fullChunkIds) {
            set.add(id);
        }
        return new CacheablePacketTable(set);
    }
}
