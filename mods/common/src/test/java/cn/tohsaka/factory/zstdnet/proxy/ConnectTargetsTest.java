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
}
