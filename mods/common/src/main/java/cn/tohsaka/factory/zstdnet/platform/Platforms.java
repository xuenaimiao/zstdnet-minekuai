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

import java.util.Objects;

/**
 * {@link Platform} 的全局持有者。
 * <p>
 * 各变体在主入口处调用 {@link #set(Platform)} 注入加载器实现；未注入时回退到
 * {@link DefaultPlatform}，保证单元测试与注入前的异常路径不会出现 NPE。
 */
public final class Platforms {

    private static volatile Platform instance = new DefaultPlatform();

    private Platforms() {
    }

    /**
     * 注入加载器平台实现。应在模组主入口尽早调用。
     */
    public static void set(Platform platform) {
        instance = Objects.requireNonNull(platform, "platform");
    }

    /**
     * 获取当前平台实现（永不为 {@code null}）。
     */
    public static Platform get() {
        return instance;
    }
}
