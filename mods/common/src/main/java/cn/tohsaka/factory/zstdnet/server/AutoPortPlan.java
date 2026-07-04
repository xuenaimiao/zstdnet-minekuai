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

import java.util.Objects;

/**
 * 专用服「自动接管」解析出的公网入口 / 后端端点。
 * <p>
 * 这是一份纯数据，故意从 {@link DedicatedServerAutoPort}（依赖 {@code net.minecraft.server.dedicated.*}）中拆出，
 * 使加载器无关核心（{@link ServerProxyRuntime}）以及不依赖 Minecraft 的插件端变体都能在不编译那个 MC 耦合文件的
 * 前提下引用本类型。由 {@link DedicatedAutoPortState} 持有当前生效的计划。
 */
final class AutoPortPlan {
    private final String listenHost;
    private final int listenPort;
    private final String targetHost;
    private final int targetPort;

    AutoPortPlan(String listenHost, int listenPort, String targetHost, int targetPort) {
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public String listenHost() {
        return this.listenHost;
    }

    public int listenPort() {
        return this.listenPort;
    }

    public String targetHost() {
        return this.targetHost;
    }

    public int targetPort() {
        return this.targetPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AutoPortPlan)) {
            return false;
        }
        AutoPortPlan other = (AutoPortPlan) o;
        return this.listenPort == other.listenPort
            && this.targetPort == other.targetPort
            && Objects.equals(this.listenHost, other.listenHost)
            && Objects.equals(this.targetHost, other.targetHost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(listenHost, listenPort, targetHost, targetPort);
    }

    @Override
    public String toString() {
        return "AutoPortPlan[listenHost=" + listenHost + ", listenPort=" + listenPort
            + ", targetHost=" + targetHost + ", targetPort=" + targetPort + "]";
    }
}
