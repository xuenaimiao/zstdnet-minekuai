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

package cn.tohsaka.factory.zstdnet.network;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「哪些 Velocity 入站连接是经 ZstdNet 代理进来的」登记表——管线注入器与正版验证之间的唯一契约。
 * <p>
 * 写入方：{@code network/VelocityPipelineInjector}。它在每条被信任（来自本机）的入站连接上读到首个握手帧、
 * 发现 ZstdNet 服务端追加的 {@code zstdnet-real-ip=} 标记后，按「Velocity 之后会对外报告的该连接地址」
 * 登记一条 {@link Info}（开启真实 IP 还原时即还原后的地址，否则是原回环地址），连接关闭时注销。
 * <p>
 * 读取方：{@code auth/VelocityPremiumAuth} 在 {@code PreLoginEvent} 里用
 * {@code InboundConnection#getRemoteAddress()} 查表，以判定该连接是否经 ZstdNet（从而需要强制离线 +
 * 走 {@code zstdnet:auth} 带外验证，保住明文以便压缩）。
 * <p>
 * 键的唯一性：注入器登记时用「真实 IP + 回环连接的本地源端口」作为报告地址，同一主机上源端口在连接存活期内唯一，
 * 故不会与其他连接撞键。
 */
public final class ZstdnetConnections {

    /**
     * 一条经 ZstdNet 进来的连接的握手信息。
     *
     * @param realIp      ZstdNet 服务端标记的玩家真实 IP（字符串形式）
     * @param rawHost     剥掉 zstdnet 标记之后、交给 Velocity 的握手 host 原文（可能仍含 {@code \0FML2\0} 等加载器标记）
     * @param protocol    握手里的协议号
     * @param createdNanos 登记时刻（{@link System#nanoTime()}），供过期清理
     */
    public record Info(String realIp, String rawHost, int protocol, long createdNanos) {
    }

    private static final Map<SocketAddress, Info> BY_REPORTED_ADDRESS = new ConcurrentHashMap<>();
    private static volatile boolean injectorActive;

    private ZstdnetConnections() {
    }

    public static void register(SocketAddress reportedAddress, Info info) {
        if (reportedAddress != null && info != null) {
            BY_REPORTED_ADDRESS.put(normalize(reportedAddress), info);
        }
    }

    public static void unregister(SocketAddress reportedAddress) {
        if (reportedAddress != null) {
            BY_REPORTED_ADDRESS.remove(normalize(reportedAddress));
        }
    }

    /**
     * 条件注销：仅当该地址当前登记的<b>仍是</b> {@code info} 时才移除。
     * <p>注入器在连接关闭时用它，避免「旧连接关闭回调晚到、而同一源端口已被新连接复用并重新登记」时误删新登记。
     */
    public static void unregister(SocketAddress reportedAddress, Info info) {
        if (reportedAddress != null && info != null) {
            BY_REPORTED_ADDRESS.remove(normalize(reportedAddress), info);
        }
    }

    /** 按 Velocity 报告的远端地址查询；非 ZstdNet 连接返回 {@code null}。 */
    public static Info lookup(SocketAddress reportedAddress) {
        return reportedAddress == null ? null : BY_REPORTED_ADDRESS.get(normalize(reportedAddress));
    }

    public static int size() {
        return BY_REPORTED_ADDRESS.size();
    }

    public static void clear() {
        BY_REPORTED_ADDRESS.clear();
    }

    /** 注入器是否已成功挂上 Velocity 的入站管线（未挂上时登记表恒为空，读取方需自行降级）。 */
    public static boolean injectorActive() {
        return injectorActive;
    }

    public static void markInjectorActive(boolean active) {
        injectorActive = active;
    }

    /** 统一成「已解析的 InetSocketAddress」，避免 unresolved / hostname 形式导致 equals 失配。 */
    private static SocketAddress normalize(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.isUnresolved()) {
            return new InetSocketAddress(inet.getHostString(), inet.getPort());
        }
        return address;
    }
}
