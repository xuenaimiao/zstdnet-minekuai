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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 实体包识别表，按 <b>Minecraft 协议版本号</b>键入（握手里的 protocol VarInt）。<b>仅编码端使用</b>——
 * 用于决定服务端→客户端某帧是否按实体包去交错、以及（B1）移动家族的定长字段布局。
 *
 * <p><b>解码端永不使用本表</b>：Layer B block 自描述，逆变换纯靠 block 内容还原（见 {@link StreamTransformCodec}）。
 * 因此本表错（packetId 不对/版本缺失）只会<b>少压</b>，绝不损坏数据——编码端对每帧做长度校验，任何不符即落回
 * {@link TransformFormat#CLASS_RAW}；即便误把非实体包归类，B2/B1 仍是"切片再拼回"的字节精确可逆操作。
 *
 * <p>缺失协议版本（未在 {@link #forProtocol} 覆盖）返回 {@code null}，调用方自动退化为 Layer A（仅长度/载荷拆分）。
 */
public final class EntityPacketTable {
    /**
     * B1 移动家族的定长字段布局（entityId 之后的各字段字节宽度）。这些包结构跨 1.19–1.21 稳定：
     * 字段宽度与具体协议号无关，只是 packetId 编号随版本变化。
     */
    public static final int[] LAYOUT_MOVE_POS = {2, 2, 2, 1};            // Δx,Δy,Δz(short) + onGround(bool)
    public static final int[] LAYOUT_MOVE_POS_ROT = {2, 2, 2, 1, 1, 1};  // Δx,Δy,Δz + yaw,pitch(angle) + onGround
    public static final int[] LAYOUT_MOVE_ROT = {1, 1, 1};              // yaw,pitch(angle) + onGround
    public static final int[] LAYOUT_VELOCITY = {2, 2, 2};             // vx,vy,vz(short)
    public static final int[] LAYOUT_HEAD_ROT = {1};                  // headYaw(angle)

    private final Map<Integer, int[]> moveLayouts;
    private final Set<Integer> entityLeading;

    private EntityPacketTable(Map<Integer, int[]> moveLayouts, Set<Integer> entityLeading) {
        this.moveLayouts = moveLayouts;
        this.entityLeading = entityLeading;
    }

    /** 该表是否没有任何可识别的实体包（此时调用方应退化为 Layer A）。 */
    public boolean isEmpty() {
        return moveLayouts.isEmpty() && entityLeading.isEmpty();
    }

    /**
     * 返回 packetId 的 B1 定长字段布局（entityId 之后各字段宽度）；非移动家族返回 {@code null}。
     * 返回的数组为只读语义，调用方不得修改。
     */
    public int[] moveLayout(int packetId) {
        return moveLayouts.get(packetId);
    }

    /** packetId 是否为"载荷以 entityId varint 开头"的实体定向包（移动家族也算）。 */
    public boolean isEntityLeading(int packetId) {
        return moveLayouts.containsKey(packetId) || entityLeading.contains(packetId);
    }

    // ---- 构造 ----

    public static Builder builder() {
        return new Builder();
    }

    /** 表构造器（生产用 {@link #forProtocol} 内部调用；单测可直接构造合成表）。 */
    public static final class Builder {
        private final Map<Integer, int[]> moveLayouts = new HashMap<>();
        private final Set<Integer> entityLeading = new HashSet<>();

        private Builder() {
        }

        /** 登记一个 B1 移动家族包：packetId + entityId 之后的定长字段宽度。 */
        public Builder move(int packetId, int[] fieldWidths) {
            moveLayouts.put(packetId, fieldWidths.clone());
            return this;
        }

        /** 登记一个 B2 实体定向包（载荷以 entityId varint 开头，其余字节不解释）。 */
        public Builder entityLeading(int packetId) {
            entityLeading.add(packetId);
            return this;
        }

        public EntityPacketTable build() {
            return new EntityPacketTable(new HashMap<>(moveLayouts), new HashSet<>(entityLeading));
        }
    }

    // ---- 按协议版本的内置表 ----

    private static final Map<Integer, EntityPacketTable> CACHE = new HashMap<>();

    /**
     * 返回该 Minecraft 协议版本的实体包表；未覆盖的版本返回 {@code null}（调用方退化为 Layer A）。
     *
     * <p>已覆盖：760=1.19.2、763=1.20.1、767=1.21.1。packetId 为各版本 clientbound PLAY 状态编号。
     * 这些编号<b>仅影响压缩率</b>（见类注释的安全性质）；新版本只需在此补一张表即可获得收益。
     */
    public static synchronized EntityPacketTable forProtocol(int protocol) {
        if (CACHE.containsKey(protocol)) {
            return CACHE.get(protocol);
        }
        EntityPacketTable table = build(protocol);
        CACHE.put(protocol, table);
        return table;
    }

    private static EntityPacketTable build(int protocol) {
        // packetId 经 PrismarineJS minecraft-data 与 ViaVersion 两套独立实现交叉核对一致。
        // 移动家族（POS/POS_ROT/ROT/VELOCITY/HEAD_ROT）走 B1 定长 SoA；其余实体定向包走 B2 抽 entityId。
        // 注意：1.20.1/1.21.1 的 0x00 为 bundle delimiter（空包），不入表 → 自动落 RAW。
        switch (protocol) {
            case 760: // Minecraft 1.19.2
                return builder()
                    .move(0x28, LAYOUT_MOVE_POS)        // Update Entity Position
                    .move(0x29, LAYOUT_MOVE_POS_ROT)    // Update Entity Position and Rotation
                    .move(0x2A, LAYOUT_MOVE_ROT)        // Update Entity Rotation
                    .move(0x52, LAYOUT_VELOCITY)        // Set Entity Velocity
                    .move(0x3F, LAYOUT_HEAD_ROT)        // Set Head Rotation
                    .entityLeading(0x66)                // Teleport Entity
                    .entityLeading(0x50)                // Set Entity Metadata
                    .entityLeading(0x53)                // Set Equipment
                    .entityLeading(0x68)                // Update Attributes
                    .entityLeading(0x00)                // Spawn Entity（1.19.2 无 bundle delimiter）
                    .build();
            case 763: // Minecraft 1.20.1
                return builder()
                    .move(0x2B, LAYOUT_MOVE_POS)        // Update Entity Position
                    .move(0x2C, LAYOUT_MOVE_POS_ROT)    // Update Entity Position and Rotation
                    .move(0x2D, LAYOUT_MOVE_ROT)        // Update Entity Rotation
                    .move(0x54, LAYOUT_VELOCITY)        // Set Entity Velocity
                    .move(0x42, LAYOUT_HEAD_ROT)        // Set Head Rotation
                    .entityLeading(0x68)                // Teleport Entity
                    .entityLeading(0x52)                // Set Entity Metadata
                    .entityLeading(0x55)                // Set Equipment
                    .entityLeading(0x6A)                // Update Attributes
                    .entityLeading(0x01)                // Spawn Entity
                    .build();
            case 767: // Minecraft 1.21.1
                return builder()
                    .move(0x2E, LAYOUT_MOVE_POS)        // Update Entity Position
                    .move(0x2F, LAYOUT_MOVE_POS_ROT)    // Update Entity Position and Rotation
                    .move(0x30, LAYOUT_MOVE_ROT)        // Update Entity Rotation
                    .move(0x5A, LAYOUT_VELOCITY)        // Set Entity Velocity
                    .move(0x48, LAYOUT_HEAD_ROT)        // Set Head Rotation
                    .entityLeading(0x70)                // Teleport Entity
                    .entityLeading(0x58)                // Set Entity Metadata
                    .entityLeading(0x5B)                // Set Equipment
                    .entityLeading(0x75)                // Update Attributes
                    .entityLeading(0x01)                // Spawn Entity
                    .build();
            default:
                return null;
        }
    }
}
