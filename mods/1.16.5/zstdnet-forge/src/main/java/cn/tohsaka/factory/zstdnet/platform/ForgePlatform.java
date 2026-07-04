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

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge 平台实现（1.16.5）。
 */
public final class ForgePlatform implements Platform {

    @Override
    public Path configDir() {
        return FMLPaths.GAMEDIR.get().resolve("config");
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public String adjustHandshakeHostSuffix(String hostSuffix) {
        if (hostSuffix != null && (hostSuffix.contains("\0FML2\0") || hostSuffix.contains("\0FML3\0"))) {
            return hostSuffix;
        }
        return "\0FML2\0";
    }

    @Override
    public boolean supportsPremiumVerification() {
        // 登录阶段正版验证依赖旧版登录流程的 coremod（ServerLoginNetHandler / ClientLoginNetHandler 等）。
        // 1.16.5 变体首轮暂不实现该挂钩，保持历史行为（绝不在无法验证时擅自把后端切离线）。
        return false;
    }
}
