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

package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.core.compress.ClientDictionaryStore;
import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.proxy.ConnectTargets;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import cn.tohsaka.factory.zstdnet.proxy.RawFallbackNotice;
import cn.tohsaka.factory.zstdnet.proxy.ZstdProbe;
import net.minecraft.client.multiplayer.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Coremod 钩子，注入 {@code ConnectingScreen.connect(String, int)} 以保证一切对外连接都经过 ZstdNet 本地代理。
 * 这修复了 ServerRedirect 等以编程方式发起连接（绕过多人界面 UI 钩子）的 mod 的兼容性。
 * <p>
 * 1.16.5 与 1.18.2+ 不同：没有静态 {@code ConnectScreen.startConnecting(...,ServerAddress,...)}，连接由
 * {@code ConnectingScreen} 构造器读取 {@code serverData.serverIP} 后调用私有 {@code connect(String ip, int port)}
 * 发起。故 coremod 改注入 {@code connect(String,int)}，把 (ip,port) 交给本钩子重写为本地代理地址：
 * 钩子返回一个 {@link ServerAddress}，coremod 再取其 getIP()/getPort() 写回局部变量。
 */
public final class ConnectScreenHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectScreenHooks.class);
    private static volatile boolean bypassing;
    private static volatile LocalZstdNet.ProxyHandle currentProxy;
    private static final Object LOCK = new Object();

    private ConnectScreenHooks() {
    }

    /**
     * 标记下一次 connect 调用为「已代理」（由 ClientProxyPublisher 在自己构造 ConnectingScreen 前调用）。
     */
    public static void setBypass(boolean bypass) {
        bypassing = bypass;
    }

    /**
     * 由 coremod 在 {@code ConnectingScreen.connect(ip, port)} 开头调用。
     * 返回一个可能改写过的 {@link ServerAddress}：若需接管则指向本地 zstd 代理，否则原样返回 (ip:port)。
     */
    public static ServerAddress interceptConnect(String ip, int port) {
        ServerAddress original = ServerAddress.fromString(ip + ":" + port);
        if (bypassing) {
            bypassing = false;
            return original;
        }
        if (ip == null) {
            return original;
        }

        // 不代理本机连接（已代理或本地）。
        if ("127.0.0.1".equals(ip) || "localhost".equals(ip) || "::1".equals(ip)) {
            return original;
        }

        String remoteAddr = ip + ":" + port;
        InetSocketAddress resolved;
        try {
            // 1.16.5 无 ServerNameResolver/ResolvedServerAddress；用 InetAddress 直接解析 A 记录用于 LAN 判定。
            resolved = new InetSocketAddress(InetAddress.getByName(ip), port);
        } catch (Exception e) {
            return original;
        }

        // 局域网/本机/私网目标：默认直连，不接管（与 ClientProxyPublisher.connect 一致）。
        if (ConnectTargets.isDirectLanTarget(resolved) && !ClientConfig.compressLan()) {
            return original;
        }

        String connectHost = resolved.getHostString();
        if (connectHost == null || connectHost.trim().isEmpty()) {
            connectHost = ip;
        }

        // 新的连接决策开始：清掉上一次连接可能残留的回退提示。
        RawFallbackNotice.clear();

        // 回退兼容（raw_fallback，默认开）：对端不说 ZSTD（樱花等联机映射的原版端口）→ 不接管，原样直连；
        // 连不上（可能离线）→ 照常接管，保留既有的登录期友好报错。与 ClientProxyPublisher.connect 一致。
        if (ClientConfig.rawFallback()
            && ZstdProbe.probe(connectHost, port) == ZstdProbe.Result.NO_ZSTD) {
            boolean knownRelay = ConnectTargets.isKnownRelayHost(ip) || ConnectTargets.isKnownRelayHost(connectHost);
            LOGGER.info("zstdnet: intercepted connect {} does not speak ZSTD -> keep vanilla direct connection (knownRelay={})", remoteAddr, knownRelay);
            RawFallbackNotice.arm(remoteAddr, knownRelay);
            return original;
        }

        try {
            synchronized (LOCK) {
                closeCurrentProxy();
                CompressionOptions compression = ClientDictionaryStore.resolveFor(
                    Platforms.get().configDir(), remoteAddr, ClientConfig.compression());
                LocalZstdNet.configureClientTransform(ClientConfig.transform());
                LocalZstdNet.configureClientCache(ClientConfig.cacheEnabled(), ClientConfig.cachePersist(), ClientConfig.cachePersistBytes());
                LocalZstdNet.ProxyHandle proxy = LocalZstdNet.start(
                    connectHost,
                    port,
                    connectHost,
                    port,
                    connectHost,
                    port,
                    ClientConfig.getLevel(),
                    compression,
                    LocalZstdNet.Mode.ZSTD
                );
                currentProxy = proxy;
                String localAddr = "127.0.0.1:" + proxy.localPort();
                LOGGER.info("zstdnet: intercepted programmatic connect {} -> local {}", remoteAddr, localAddr);
                return ServerAddress.fromString(localAddr);
            }
        } catch (IOException e) {
            LOGGER.warn("zstdnet: failed to start proxy for intercepted connect {}: {}", remoteAddr, e.toString());
            return original;
        }
    }

    /**
     * coremod 辅助：取 {@link ServerAddress} 的主机串。由注入到 {@code ConnectingScreen.connect} 的字节码调用，
     * 以避免在 coremod 里直接生成对混淆 {@code ServerAddress.getIP()} 的调用（其 SRG 名运行期才定）——
     * 本方法是普通 Java，经 reobf 后 {@code getIP()} 会被正确映射到运行期名。
     */
    public static String hostOf(ServerAddress address) {
        return address.getIP();
    }

    /** coremod 辅助：取 {@link ServerAddress} 的端口。理由同 {@link #hostOf(ServerAddress)}。 */
    public static int portOf(ServerAddress address) {
        return address.getPort();
    }

    public static LocalZstdNet.ProxyHandle takeProxy() {
        synchronized (LOCK) {
            LocalZstdNet.ProxyHandle proxy = currentProxy;
            currentProxy = null;
            return proxy;
        }
    }

    private static void closeCurrentProxy() {
        if (currentProxy != null) {
            try {
                currentProxy.close();
            } catch (Exception ignored) {
            }
            currentProxy = null;
        }
    }
}
