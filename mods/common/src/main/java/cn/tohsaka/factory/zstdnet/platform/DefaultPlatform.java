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
