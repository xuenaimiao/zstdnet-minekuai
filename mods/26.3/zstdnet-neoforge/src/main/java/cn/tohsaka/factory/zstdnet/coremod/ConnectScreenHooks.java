package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.core.compress.ClientDictionaryStore;
import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.proxy.ConnectTargets;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import cn.tohsaka.factory.zstdnet.proxy.RawFallbackNotice;
import cn.tohsaka.factory.zstdnet.proxy.ZstdProbe;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class ConnectScreenHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectScreenHooks.class);
    private static volatile boolean bypassing;
    /**
     * 下一次 startConnecting 要改投的本地代理地址（ClientProxyPublisher 设置，钩子取用一次即清空）。
     * 见 {@link #redirectNext}。
     */
    private static volatile ServerAddress pendingRedirect;
    private static volatile LocalZstdNet.ProxyHandle currentProxy;
    private static final Object LOCK = new Object();

    private ConnectScreenHooks() {
    }

    public static void setBypass(boolean bypass) {
        bypassing = bypass;
    }

    /**
     * 让下一次 {@code ConnectScreen.startConnecting} 在方法体开头改投到 {@code target}（本地 zstd 代理）。
     *
     * <p>调用方给 startConnecting 传的是<b>真实服务器地址</b>：包在 startConnecting 外层的模组据此识别服务器——
     * AutoModpack 5 用它记录整合包来源（origin）并按地址存证书指纹。若直接把 127.0.0.1:随机端口 传进去，
     * 本地代理端口每次都不同，AMP 每次进服都会弹「新服务器地址」并断开登录，玩家永远进不去。
     * 改投发生在方法体内（coremod 先于 Mixin、MixinExtras 的 @WrapMethod 在其它注入之后才包裹），
     * 所以外层包装看到真实地址，原版连接逻辑照常连本地代理。
     */
    public static void redirectNext(ServerAddress target) {
        pendingRedirect = target;
    }

    /** 取走并清空未被钩子消费的改投目标；返回非 null 说明本次 startConnecting 没有经过钩子。 */
    public static ServerAddress takeUnusedRedirect() {
        ServerAddress unused = pendingRedirect;
        pendingRedirect = null;
        return unused;
    }

    public static ServerAddress interceptConnect(ServerAddress original, ServerData serverData) {
        ServerAddress redirect = pendingRedirect;
        if (redirect != null) {
            // ClientProxyPublisher 已起好本地代理、传入真实地址：在这里改投，外层包装仍看到真实地址。
            pendingRedirect = null;
            bypassing = false;
            return redirect;
        }
        if (bypassing) {
            bypassing = false;
            return original;
        }

        if (original == null) {
            return original;
        }

        String host = original.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)) {
            return original;
        }

        String remoteAddr = host + ":" + original.getPort();
        ResolvedServerAddress resolved = ServerNameResolver.DEFAULT.resolveAddress(original).orElse(null);
        if (resolved == null) {
            return original;
        }

        // 局域网/本机/私网目标：默认直连，不接管。
        if (ConnectTargets.isDirectLanTarget(resolved.asInetSocketAddress()) && !ClientConfig.compressLan()) {
            return original;
        }

        String connectHost = resolved.asInetSocketAddress().getHostString();
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = resolved.getHostName();
        }
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = host;
        }

        // 新的连接决策开始：清掉上一次连接可能残留的回退提示。
        RawFallbackNotice.clear();

        // 回退兼容（raw_fallback，默认开）：对端不说 ZSTD（樱花等联机映射的原版端口）→ 不接管，原样直连；
        // 连不上（可能离线）→ 照常接管，保留既有的登录期友好报错。与 ClientProxyPublisher.connect 一致。
        if (ClientConfig.rawFallback()
            && ZstdProbe.probe(connectHost, resolved.getPort()) == ZstdProbe.Result.NO_ZSTD) {
            boolean knownRelay = ConnectTargets.isKnownRelayHost(host) || ConnectTargets.isKnownRelayHost(connectHost);
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
                    resolved.getPort(),
                    connectHost,
                    resolved.getPort(),
                    connectHost,
                    resolved.getPort(),
                    ClientConfig.getLevel(),
                    compression,
                    LocalZstdNet.Mode.ZSTD
                );
                currentProxy = proxy;
                String localAddr = "127.0.0.1:" + proxy.localPort();
                LOGGER.info("zstdnet: intercepted programmatic connect {} -> local {}", remoteAddr, localAddr);

                if (serverData != null) {
                    serverData.ip = remoteAddr;
                }

                return ServerAddress.parseString(localAddr);
            }
        } catch (IOException e) {
            LOGGER.warn("zstdnet: failed to start proxy for intercepted connect {}: {}", remoteAddr, e.toString());
            return original;
        }
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
