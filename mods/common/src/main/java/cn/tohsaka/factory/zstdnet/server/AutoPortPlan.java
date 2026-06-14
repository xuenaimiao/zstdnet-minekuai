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
 * 专用服「自动接管」解析出的公网入口 / 后端端点。
 * <p>
 * 这是一份纯数据，故意从 {@link DedicatedServerAutoPort}（依赖 {@code net.minecraft.server.dedicated.*}）中拆出，
 * 使加载器无关核心（{@link ServerProxyRuntime}）以及不依赖 Minecraft 的插件端变体都能在不编译那个 MC 耦合文件的
 * 前提下引用本类型。由 {@link DedicatedAutoPortState} 持有当前生效的计划。
 */
record AutoPortPlan(
    String listenHost,
    int listenPort,
    String targetHost,
    int targetPort
) {
}
