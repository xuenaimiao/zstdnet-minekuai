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

import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumAuth;
import cn.tohsaka.factory.zstdnet.network.VelocityPipelineInjector;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.platform.VelocityPlatform;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Velocity 代理端插件主入口。
 * <p>
 * 复用 {@code mods/common} 的 {@link cn.tohsaka.factory.zstdnet.server.ServerProxyRuntime} 代理核心：
 * Velocity 照常占用自己的 bind 端口给原版玩家直连；本插件在另一个监听端口上启动 zstd 代理，
 * 把装了 ZstdNet 客户端 mod 的玩家的压缩流量解压后经本机回环转发给 Velocity。
 * <p>
 * 插件描述文件 {@code velocity-plugin.json} 由构建从模板展开（不走注解处理器），故主类无需 {@code @Plugin} 注解。
 */
public final class Zstdnet {

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public Zstdnet(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        // 必须最先：注入平台实现，让 common 核心从插件数据目录读写配置。
        Platforms.set(new VelocityPlatform(dataDirectory));
    }

    /**
     * 在 Velocity 绑定监听端口之前触发：管线注入器必须在此时挂上（Velocity 在 bind 时才读取子通道初始化器）。
     * FIRST 优先级：先于其他插件（如 ViaVersion）包装初始化器，保证我方处理器位于管线最前、读到的是原始字节。
     */
    @Subscribe(order = PostOrder.FIRST)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        ServerProxyBootstrap.init(server, logger);
        // 还原经代理进来的玩家真实 IP，并对经 ZstdNet 的连接关闭 Velocity 自身的 zlib 压缩（否则 zstd 压不动）。
        VelocityPipelineInjector.init(server, logger);
        // 经 ZstdNet 的连接走 zstdnet:auth 带外正版验证（不开 AES，保住明文以便压缩）。
        VelocityPremiumAuth.init(this, server, logger);

        CommandMeta meta = server.getCommandManager().metaBuilder("zstdnet").plugin(this).build();
        server.getCommandManager().register(meta, new ServerProxyBootstrap.Command());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        VelocityPremiumAuth.shutdown();
        VelocityPipelineInjector.shutdown();
        ServerProxyBootstrap.shutdown();
    }
}
