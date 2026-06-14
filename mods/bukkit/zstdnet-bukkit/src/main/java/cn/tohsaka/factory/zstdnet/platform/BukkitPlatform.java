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

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/**
 * 插件端的 {@link Platform} 实现。
 * <p>
 * 配置目录指向插件数据目录（{@code plugins/ZstdNet/}），于是 common 的
 * {@code ServerProxyConfigFile} 会在此读写 {@code zstdnet-server.properties} 及字典目录；
 * 插件永远是服务端，故 {@link #isClient()} 恒为 {@code false}，握手后缀用默认（原样返回）。
 */
public final class BukkitPlatform implements Platform {

    private final Path configDir;

    public BukkitPlatform(Plugin plugin) {
        this.configDir = plugin.getDataFolder().toPath();
    }

    @Override
    public Path configDir() {
        return configDir;
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
