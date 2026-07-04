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

import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import cn.tohsaka.factory.zstdnet.network.DictionarySync;
import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import cn.tohsaka.factory.zstdnet.network.VoicePortSync;
import cn.tohsaka.factory.zstdnet.platform.NeoForgePlatform;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主入口。
 * <p>
 * 客户端侧初始化线路发布器，服务端侧初始化内置代理启动器。
 */
@Mod(Zstdnet.MODID)
public class Zstdnet {
    public static final String MODID = "zstdnet";

    private static final Logger LOGGER = LoggerFactory.getLogger(Zstdnet.class);

    /**
     * 模组加载时注册客户端与服务端功能。
     */
    public Zstdnet(IEventBus modEventBus, ModContainer modContainer) {
        Platforms.set(new NeoForgePlatform());
        LanCompressionSync.init(modEventBus);
        DictionarySync.init(modEventBus);
        VoicePortSync.init(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientProxyPublisher.init(modContainer);
        }
        ServerProxyBootstrap.init();
        LOGGER.info("zstdnet loaded");
    }
}
