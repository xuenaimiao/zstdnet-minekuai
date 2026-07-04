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

package cn.tohsaka.factory.zstdnet.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;

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

    /**
     * 已知「联机映射 / 内网穿透」服务的节点域名后缀。
     *
     * <p>这类服务（樱花frp / OpenFrp 等）常被用来把「开放到局域网」的原版端口直接映射到公网——
     * 此时映射地址虽是公网域名，但端口后面并没有 ZstdNet 服务端，强制 ZSTD 只会把玩家挡在门外。
     * 该列表仅用于日志与聊天提示的措辞（“识别为联机映射节点”），<b>不改变</b>连接决策：
     * 是否回退直连由 {@code ZstdProbe} 的实际探测结果决定——真挂在 frp 后面的 ZstdNet
     * 服务器（本 mod 的主场景）探测能通过，照常压缩，不受此列表影响。</p>
     */
    private static final String[] KNOWN_RELAY_HOST_SUFFIXES = {
        // SakuraFrp（樱花frp）：节点域名（新 natfrp.cloud / 旧 sakurafrp.com）与子域绑定 nyat.app
        ".natfrp.cloud",
        ".natfrp.com",
        ".sakurafrp.com",
        ".nyat.app",
        // OpenFrp：节点域名
        ".ofalias.com",
        ".ofalias.net",
    };

    /**
     * @param host 用户输入 / SRV 解析后的目标主机名（IP 直接回 {@code false}）
     * @return 是否为已知联机映射（樱花frp / OpenFrp 等）节点域名
     */
    public static boolean isKnownRelayHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (String suffix : KNOWN_RELAY_HOST_SUFFIXES) {
            // 只认真正的子域（node1.natfrp.cloud），不把裸域名（natfrp.com 官网）算作节点。
            if (normalized.length() > suffix.length() && normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
