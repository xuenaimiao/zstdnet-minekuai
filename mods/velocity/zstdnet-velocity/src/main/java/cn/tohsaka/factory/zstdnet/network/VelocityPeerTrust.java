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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「这条连接是不是本机进程发起的」——只有本机来的连接才采信 zstdnet 标记（与 mod 变体的
 * {@code isTrustedLocalProxy}、Bukkit 插件的回环规则同一条安全边界，这里再放宽到本机网卡地址：
 * Velocity 绑在具体网卡地址上时，ZstdNet 代理的 {@code target} 会指向那个地址，源地址也就是它）。
 *
 * <p>本机网卡地址表缓存在内存里（{@link #REFRESH_INTERVAL_NANOS} 过期），过期后在后台线程刷新、
 * 期间继续用旧表——判断跑在 netty 事件循环上，绝不能在这里枚举网卡阻塞。公网玩家的判定只是一次哈希查找。</p>
 */
final class VelocityPeerTrust {

    private static final long REFRESH_INTERVAL_NANOS = 60_000_000_000L;

    private static volatile Set<InetAddress> localAddresses = Collections.emptySet();
    private static volatile long refreshedAt;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();

    private VelocityPeerTrust() {
    }

    /** 回环 / 通配 / 本机任一网卡上的地址 → 可信。 */
    static boolean isTrusted(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            return false;
        }
        InetAddress ip = ((InetSocketAddress) address).getAddress();
        if (ip == null) {
            return false;
        }
        if (ip.isLoopbackAddress() || ip.isAnyLocalAddress()) {
            return true;
        }
        if (System.nanoTime() - refreshedAt > REFRESH_INTERVAL_NANOS) {
            refreshAsync();
        }
        return localAddresses.contains(ip);
    }

    /** 同步刷新一次（插件初始化时调用，不在事件循环上）。 */
    static void refreshNow() {
        Set<InetAddress> addresses = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                Enumeration<InetAddress> ips = nic.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    try {
                        // 去掉 scope id，按纯地址字节比较。
                        addresses.add(InetAddress.getByAddress(ip.getAddress()));
                    } catch (Exception ignored) {
                        addresses.add(ip);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败：保留旧表（至少回环判定不受影响）。
            refreshedAt = System.nanoTime();
            return;
        }
        localAddresses = Collections.unmodifiableSet(addresses);
        refreshedAt = System.nanoTime();
    }

    private static void refreshAsync() {
        if (!REFRESHING.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread thread = new Thread(() -> {
                try {
                    refreshNow();
                } finally {
                    REFRESHING.set(false);
                }
            }, "zstdnet-velocity-nic-refresh");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            REFRESHING.set(false);
        }
    }
}
