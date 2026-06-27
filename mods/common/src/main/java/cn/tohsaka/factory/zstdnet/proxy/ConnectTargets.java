/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 判定一个连接目标是否属于「局域网 / 本机」范畴。
 *
 * <p>用途：客户端接管前先看目标地址——内网 / 环回 / 链路本地一律视为局域网，
 * 默认走原版直连、不起压缩代理（局域网带宽充裕，压缩无收益且徒增 CPU）。
 * 只有公网 IP / 域名才进 ZSTD 压缩通道。这样「开放到局域网」与同网段联机的体验与不装 mod 一致。</p>
 *
 * <p>覆盖范围：{@code 127.0.0.0/8}、{@code ::1}、{@code 0.0.0.0}（任意本地）、
 * {@code 10/8}·{@code 172.16-31}·{@code 192.168/16}（RFC1918 私网，{@link InetAddress#isSiteLocalAddress()}）、
 * {@code 169.254/16}·{@code fe80::/10}（链路本地），外加 {@code fc00::/7} IPv6 ULA
 * （Java 的 {@code isSiteLocalAddress} 不覆盖 IPv6 ULA，单独补判）。</p>
 */
public final class ConnectTargets {

    private ConnectTargets() {
    }

    /**
     * @param addr 已解析的目标地址；{@code null}（未解析）按「非局域网」处理，回 {@code false}
     * @return 该地址是否为局域网 / 本机目标
     */
    public static boolean isDirectLanTarget(InetAddress addr) {
        if (addr == null) {
            return false;
        }
        if (addr.isLoopbackAddress()
            || addr.isAnyLocalAddress()
            || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 ULA：fc00::/7（首字节 0xFC 或 0xFD）。isSiteLocalAddress 不覆盖它。
        byte[] raw = addr.getAddress();
        if (raw != null && raw.length == 16) {
            int first = raw[0] & 0xFF;
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }

    /**
     * @param sock 已解析的目标 socket 地址；{@code null} 或未解析（地址为空）按「非局域网」处理
     * @return 该地址是否为局域网 / 本机目标
     */
    public static boolean isDirectLanTarget(InetSocketAddress sock) {
        return sock != null && isDirectLanTarget(sock.getAddress());
    }
}
