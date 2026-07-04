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

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 局域网 / 本机目标判定：内网·环回·链路本地 → 直连；公网 → 压缩。
 */
class ConnectTargetsTest {

    private static InetAddress ip(String literal) throws Exception {
        // 字面量 IP，getByName 不触发 DNS。
        return InetAddress.getByName(literal);
    }

    @Test
    void loopbackIsDirect() throws Exception {
        assertTrue(ConnectTargets.isDirectLanTarget(ip("127.0.0.1")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("127.10.20.30")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("::1")));
    }

    @Test
    void anyLocalIsDirect() throws Exception {
        assertTrue(ConnectTargets.isDirectLanTarget(ip("0.0.0.0")));
    }

    @Test
    void rfc1918PrivateIsDirect() throws Exception {
        assertTrue(ConnectTargets.isDirectLanTarget(ip("10.0.0.5")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("172.16.0.1")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("172.31.255.255")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("192.168.1.20")));
    }

    @Test
    void linkLocalIsDirect() throws Exception {
        assertTrue(ConnectTargets.isDirectLanTarget(ip("169.254.10.10")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("fe80::1")));
    }

    @Test
    void ipv6UlaIsDirect() throws Exception {
        assertTrue(ConnectTargets.isDirectLanTarget(ip("fc00::1")));
        assertTrue(ConnectTargets.isDirectLanTarget(ip("fd12:3456:789a::1")));
    }

    @Test
    void publicIsNotDirect() throws Exception {
        assertFalse(ConnectTargets.isDirectLanTarget(ip("8.8.8.8")));
        assertFalse(ConnectTargets.isDirectLanTarget(ip("1.1.1.1")));
        // 172.32.x 已超出 172.16/12 私网段，属公网。
        assertFalse(ConnectTargets.isDirectLanTarget(ip("172.32.0.1")));
        assertFalse(ConnectTargets.isDirectLanTarget(ip("2001:4860:4860::8888")));
    }

    @Test
    void nullIsNotDirect() {
        assertFalse(ConnectTargets.isDirectLanTarget((InetAddress) null));
        assertFalse(ConnectTargets.isDirectLanTarget((InetSocketAddress) null));
        // 未解析的 socket 地址（address 为 null）也按非局域网处理。
        assertFalse(ConnectTargets.isDirectLanTarget(InetSocketAddress.createUnresolved("example.com", 25565)));
    }

    @Test
    void socketOverloadDelegates() throws Exception {
        assertTrue(ConnectTargets.isDirectLanTarget(new InetSocketAddress(ip("192.168.0.1"), 25565)));
        assertFalse(ConnectTargets.isDirectLanTarget(new InetSocketAddress(ip("8.8.8.8"), 25565)));
    }

    @Test
    void knownRelayHostsAreRecognized() {
        // SakuraFrp（樱花frp）节点域名 / 子域绑定
        assertTrue(ConnectTargets.isKnownRelayHost("cn-hk-nf-1.natfrp.cloud"));
        assertTrue(ConnectTargets.isKnownRelayHost("cn-sy-plc-1.sakurafrp.com"));
        assertTrue(ConnectTargets.isKnownRelayHost("mc.example.nyat.app"));
        // OpenFrp 节点域名
        assertTrue(ConnectTargets.isKnownRelayHost("example.ofalias.com"));
        assertTrue(ConnectTargets.isKnownRelayHost("node2.ofalias.net"));
        // 大小写 / 末尾点容忍
        assertTrue(ConnectTargets.isKnownRelayHost("CN-HK-NF-1.NatFrp.Cloud"));
        assertTrue(ConnectTargets.isKnownRelayHost("cn-hk-nf-1.natfrp.cloud."));
    }

    @Test
    void nonRelayHostsAreNotRecognized() {
        assertFalse(ConnectTargets.isKnownRelayHost(null));
        assertFalse(ConnectTargets.isKnownRelayHost(""));
        assertFalse(ConnectTargets.isKnownRelayHost("mc.hypixel.net"));
        assertFalse(ConnectTargets.isKnownRelayHost("8.8.8.8"));
        // 裸域名（官网）不算节点；形似但不同的域名不误判。
        assertFalse(ConnectTargets.isKnownRelayHost("natfrp.com"));
        assertFalse(ConnectTargets.isKnownRelayHost("nyat.app"));
        assertFalse(ConnectTargets.isKnownRelayHost("evil-natfrp.cloud.example.com"));
    }
}
