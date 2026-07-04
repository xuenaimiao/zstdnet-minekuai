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

package cn.tohsaka.factory.zstdnet.platform;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;

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

    /**
     * 语音<b>插件</b>（Simple Voice Chat / Plasmo Voice 等）的配置在 {@code plugins/<VoiceMod>/}，
     * 即本插件数据目录（{@code plugins/ZstdNet/}）的同级目录。故搜索根取 {@code plugins/} 目录。
     */
    @Override
    public List<Path> voiceConfigRoots() {
        Path plugins = configDir.getParent();
        return plugins != null ? List.of(plugins) : List.of(configDir);
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
