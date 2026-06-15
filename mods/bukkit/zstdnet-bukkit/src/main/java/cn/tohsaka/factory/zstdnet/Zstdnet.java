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

package cn.tohsaka.factory.zstdnet;

import cn.tohsaka.factory.zstdnet.network.VoicePortSync;
import cn.tohsaka.factory.zstdnet.platform.BukkitPlatform;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件端主入口（Bukkit / Spigot / Paper / Purpur，以及 Arclight、Mohist 等混合端）。
 * <p>
 * 复用 {@code mods/common} 的 {@link cn.tohsaka.factory.zstdnet.server.ServerProxyRuntime} 代理核心：
 * MC 服务器照常占用 server-port 给原版玩家直连；本插件在另一个监听端口上启动 zstd 代理，
 * 把装了 ZstdNet 客户端 mod 的玩家的压缩流量解压后转发回本机后端端口。
 */
public final class Zstdnet extends JavaPlugin {

    @Override
    public void onEnable() {
        // 必须第一行：注入插件平台实现，让 common 核心从插件数据目录读写配置。
        Platforms.set(new BukkitPlatform(this));
        ServerProxyBootstrap.initBukkit(this);
        // 进服时把探测到的语音端口下发给 ZstdNet 客户端 mod（零配置兼容各类语音 mod）。
        VoicePortSync.init(this);
    }

    @Override
    public void onDisable() {
        ServerProxyBootstrap.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("zstdnet")) {
            return ServerProxyBootstrap.handleCommand(sender, args);
        }
        return false;
    }
}
