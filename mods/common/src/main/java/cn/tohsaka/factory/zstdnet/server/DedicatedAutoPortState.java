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

package cn.tohsaka.factory.zstdnet.server;

/**
 * 当前生效的「自动接管」计划的持有者（MC 无关）。
 * <p>
 * 从 {@link DedicatedServerAutoPort} 拆出状态部分：那个类依赖 {@code net.minecraft.server.dedicated.*}，
 * 在插件端变体里会被排除编译；而 {@link ServerProxyRuntime} 仍需读取活动计划，故把这份 volatile 状态放到本
 * MC 无关类里。专用服由 {@code DedicatedServerAutoPort} 在准备配置时 {@link #set(AutoPortPlan)}；
 * 插件端从不挪后端端口，{@link #activePlan()} 恒为 {@code null}。
 */
final class DedicatedAutoPortState {

    private static volatile AutoPortPlan activePlan;

    private DedicatedAutoPortState() {
    }

    static AutoPortPlan activePlan() {
        return activePlan;
    }

    static void set(AutoPortPlan plan) {
        activePlan = plan;
    }

    static void clear() {
        activePlan = null;
    }
}
