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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 未注入加载器实现时的保守回退实现。
 * <p>
 * 采用相对工作目录的 {@code config} 目录（运行时工作目录即游戏目录，结果与各加载器一致），
 * 默认按服务端环境处理，握手后缀原样返回。仅用于测试或注入前兜底，正式运行时会被各变体覆盖。
 */
final class DefaultPlatform implements Platform {

    @Override
    public Path configDir() {
        return Paths.get("config");
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
