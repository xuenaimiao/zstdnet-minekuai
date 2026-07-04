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

package cn.tohsaka.factory.zstdnet.core.cache;

import java.util.Locale;

/**
 * 区块引用缓存（CRC, Chunk Reference Cache）的运行模式，对应服务端配置键 {@code chunk_cache}。
 *
 * <ul>
 *   <li>{@link #OFF} —— 关闭（默认）。与历史行为逐字节一致、零开销。</li>
 *   <li>{@link #MEASURE} —— 仅测量：不改任何字节，统计“可引用缓存去重”的重复流量、以及现有 LDM 窗口
 *       已能折叠多少 / 还剩多少边际空间。作为是否上 REF 缓存的决策闸门。</li>
 *   <li>{@link #REF} —— 显式区块引用缓存去重（相同区块重发 → 8 字节 REF 令牌）。</li>
 *   <li>{@link #AUTO} —— <b>默认值</b>：智能默认，协商成功即自动启用 REF + 跨会话 WARM_REF + 自适应旁路，
 *       并在生效版本>=3 且划算时叠加结构无关 PATCH。遇合适情景自动生效，不支持的客户端逐字节透传。</li>
 *   <li>{@link #FULL} —— REF + WARM_REF + 字节级 PATCH（确定性，不自适应旁路）。</li>
 * </ul>
 *
 * <p>无论哪种启用模式，都靠握手协商门控：仅当客户端也 advertise ccache 能力时才对该连接生效，
 * 对未升级 / 不支持的客户端逐字节透传、任何异常 fail-closed。
 */
public enum CacheMode {
    OFF,
    MEASURE,
    REF,
    FULL,
    AUTO;

    /** 解析配置字符串；未知 / 空 / {@code off}/{@code false} 一律视为 {@link #OFF}。 */
    public static CacheMode parse(String raw) {
        if (raw == null) {
            return OFF;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "measure":
                return MEASURE;
            case "ref":
                return REF;
            case "full":
                return FULL;
            case "auto":
                return AUTO;
            default:
                return OFF;
        }
    }

    /** 是否启用区块引用缓存（REF/FULL/AUTO 都启用 REF + WARM_REF）。 */
    public boolean engagesCache() {
        return this == REF || this == FULL || this == AUTO;
    }

    /** 是否在划算时叠加结构无关 PATCH（仅 FULL/AUTO；REF 只发显式 REF/WARM_REF）。还须生效版本>=3 才真正发出。 */
    public boolean engagesPatch() {
        return this == FULL || this == AUTO;
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
