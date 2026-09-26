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

package cn.tohsaka.factory.zstdnet.client;

/**
 * 由 {@code mixin/WorldOptionsScreenMixin} 织进 26.3 的 {@code WorldOptionsScreen}（鸭子接口）：
 * 让薄层读到原版解析出的局域网端口，并在 Zstd 端口变化后刷新原版「应用更改」按钮的可用状态。
 * 放在 mixin 包之外——mixin 包里的类不能被普通代码直接引用。
 */
public interface WorldOptionsLanAccess {
    /** 原版 {@code WorldOptionsScreen.port}：本次局域网实际使用的游戏端口（尚未解析时为 0）。 */
    int zstdnet$lanPort();

    /** 调用原版 {@code updateApplyChangesActiveState()}，按最新校验结果重新决定「应用更改」是否可点。 */
    void zstdnet$refreshApplyChanges();
}
