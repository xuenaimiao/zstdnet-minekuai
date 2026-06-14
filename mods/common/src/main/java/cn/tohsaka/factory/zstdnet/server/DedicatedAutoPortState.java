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

package cn.tohsaka.factory.zstdnet.server;

/**
 * 当前生效的「自动接管」计划的持有者（MC 无关）。
 * <p>
 * 从 {@link DedicatedServerAutoPort} 拆出状态部分：那个类依赖 {@code net.minecraft.server.dedicated.*}，
 * 在插件端变体里会被排除编译；而 {@link ServerProxyRuntime} 仍需读取活动计划，故把这份 volatile 状态放到本
 * MC 无关类里。专用服由 {@code DedicatedServerAutoPort} 在准备配置时 {@link #set(AutoPortPlan)}；
 * 插件端从不挪后端端口，{@link #activePlan()} 恒为 {@code null}。
 */
final class DedicatedAutoPortState {

    private static volatile AutoPortPlan activePlan;

    private DedicatedAutoPortState() {
    }

    static AutoPortPlan activePlan() {
        return activePlan;
    }

    static void set(AutoPortPlan plan) {
        activePlan = plan;
    }

    static void clear() {
        activePlan = null;
    }
}
