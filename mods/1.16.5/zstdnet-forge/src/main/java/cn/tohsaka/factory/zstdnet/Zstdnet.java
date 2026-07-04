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
import cn.tohsaka.factory.zstdnet.platform.ForgePlatform;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主入口（1.16.5）。
 * <p>
 * 客户端侧初始化线路发布器，服务端侧初始化内置代理启动器。
 */
@Mod(Zstdnet.MODID)
public class Zstdnet {
    public static final String MODID = "zstdnet";

    // 1.16.5 无 com.mojang.logging.LogUtils（1.17+ 才有）；用 slf4j（forge 的 log4j-slf4j18-impl 提供）。
    private static final Logger LOGGER = LoggerFactory.getLogger(Zstdnet.class);

    /**
     * 模组加载时注册客户端与服务端功能。
     */
    public Zstdnet() {
        Platforms.set(new ForgePlatform());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientProxyPublisher.init();
        }
        LanCompressionSync.init();
        DictionarySync.init();
        VoicePortSync.init();
        ServerProxyBootstrap.init();
        LOGGER.info("zstdnet loaded");
    }
}
