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
