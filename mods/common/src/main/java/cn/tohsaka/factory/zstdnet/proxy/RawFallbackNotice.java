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

package cn.tohsaka.factory.zstdnet.proxy;

/**
 * raw_fallback 回退直连的「进服提示」暂存：连接决策发生在点击加入时（还没有玩家实体可发消息），
 * 提示要等玩家真正进入世界后才能打进聊天栏。连接路径在此登记（{@link #arm}），各变体
 * ClientProxyPublisher 的客户端 tick 里发现「已在多人世界 + 有待发提示」时取走并打印
 * （{@link #take}，取即清，保证只打一次）。
 *
 * <p>全局单槽即可：一个游戏实例同一时刻只有一条对外连接；每次新的连接决策都会先
 * {@link #clear()} 再按需 {@link #arm}，上一次连接失败留下的陈旧提示不会串到下个服务器。</p>
 */
public final class RawFallbackNotice {
    private static volatile Notice pending;

    private RawFallbackNotice() {
    }

    /** 登记一条待发提示（在回退直连的连接路径上调用）。 */
    public static void arm(String address, boolean knownRelay) {
        pending = new Notice(address, knownRelay);
    }

    /** 清除待发提示（每次新的连接决策开始时调用）。 */
    public static void clear() {
        pending = null;
    }

    /** 取走待发提示（取即清）；无待发时回 {@code null}。 */
    public static Notice take() {
        Notice notice = pending;
        if (notice != null) {
            pending = null;
        }
        return notice;
    }

    /** 一条待发提示：连接地址 + 是否识别为已知联机映射（樱花frp 等）节点。 */
    public static final class Notice {
        private final String address;
        private final boolean knownRelay;

        private Notice(String address, boolean knownRelay) {
            this.address = address;
            this.knownRelay = knownRelay;
        }

        public String address() {
            return this.address;
        }

        public boolean knownRelay() {
            return this.knownRelay;
        }
    }
}
