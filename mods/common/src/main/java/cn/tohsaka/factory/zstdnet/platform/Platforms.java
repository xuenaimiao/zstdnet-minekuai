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

package cn.tohsaka.factory.zstdnet.platform;

import java.util.Objects;

/**
 * {@link Platform} 的全局持有者。
 * <p>
 * 各变体在主入口处调用 {@link #set(Platform)} 注入加载器实现；未注入时回退到
 * {@link DefaultPlatform}，保证单元测试与注入前的异常路径不会出现 NPE。
 */
public final class Platforms {

    private static volatile Platform instance = new DefaultPlatform();

    private Platforms() {
    }

    /**
     * 注入加载器平台实现。应在模组主入口尽早调用。
     */
    public static void set(Platform platform) {
        instance = Objects.requireNonNull(platform, "platform");
    }

    /**
     * 获取当前平台实现（永不为 {@code null}）。
     */
    public static Platform get() {
        return instance;
    }
}
