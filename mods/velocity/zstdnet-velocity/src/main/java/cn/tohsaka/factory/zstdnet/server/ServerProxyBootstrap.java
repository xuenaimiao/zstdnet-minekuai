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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Velocity 代理端引导：把 common 的 {@link ServerProxyRuntime} 接入 Velocity 插件生命周期。
 * <p>
 * 与 Bukkit 插件同一模型：Velocity 照常占用自己的 {@code bind} 端口给原版玩家直连；本插件在另一个监听端口上启动
 * zstd 代理，把装了 ZstdNet 客户端 mod 的玩家的压缩流量解压后经本机回环转发给 Velocity。
 * <p>
 * 本类与 {@code ServerProxyRuntime} 同包，因此可调用其包私有的 {@code start/stop/...} 方法。职责：
 * <ul>
 *   <li>{@code ProxyInitializeEvent}：确保配置存在（缺失则写插件端默认：auto_takeover=false、独立监听端口）→ 启动代理；</li>
 *   <li>用独立守护线程周期轮询配置文件变更做热重载；</li>
 *   <li>{@code /zstdnet status|reload|start|stop} 管理命令（代理端命令，权限 {@code zstdnet.admin}）。</li>
 * </ul>
 */
public final class ServerProxyBootstrap {

    private static final ServerProxyRuntime RUNTIME = new ServerProxyRuntime();
    /** 配置热重载轮询间隔（秒）。 */
    private static final long WATCH_INTERVAL_SECONDS = 5L;
    public static final String ADMIN_PERMISSION = "zstdnet.admin";

    private static Logger logger;
    private static ScheduledExecutorService watcherExec;
    private static ScheduledFuture<?> watcher;
    private static volatile int backendPort;
    private static volatile String backendHost = "127.0.0.1";

    private ServerProxyBootstrap() {
    }

    /**
     * {@code ProxyInitializeEvent} 时调用：准备配置、启动代理、注册配置热重载轮询。
     */
    public static void init(ProxyServer server, Logger log) {
        logger = log;
        InetSocketAddress bound = server.getBoundAddress();
        backendPort = bound.getPort();
        if (bound.getAddress() != null && !bound.getAddress().isAnyLocalAddress()) {
            backendHost = bound.getAddress().getHostAddress();
        }

        ensureConfig(backendPort);
        RUNTIME.start(backendPort);
        logProxyState("started");

        // 独立守护线程轮询配置磁盘变更：代理 start/stop 自带 lifecycleLock，tick() 只触碰 RUNTIME 与 logger，
        // 与 Velocity 的调度器/线程模型无关。
        watcherExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zstdnet-velocity-config-watcher");
            t.setDaemon(true);
            return t;
        });
        watcher = watcherExec.scheduleWithFixedDelay(
            ServerProxyBootstrap::tick, WATCH_INTERVAL_SECONDS, WATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * {@code ProxyShutdownEvent} 时调用：停止轮询并关闭代理。
     */
    public static void shutdown() {
        if (watcher != null) {
            watcher.cancel(false);
            watcher = null;
        }
        if (watcherExec != null) {
            watcherExec.shutdownNow();
            watcherExec = null;
        }
        RUNTIME.stop();
    }

    public static boolean isRunning() {
        return RUNTIME.isRunning();
    }

    /** 当前 zstd 代理的对外监听端口；未运行时返回 -1。 */
    public static int listenPort() {
        ServerProxyRuntime.HudSnapshot s = RUNTIME.hudSnapshot();
        return RUNTIME.isRunning() && s != null ? s.listenPort() : -1;
    }

    public static int backendPort() {
        return backendPort;
    }

    private static void ensureConfig(int backend) {
        if (Files.exists(ServerProxyConfigFile.path())) {
            // 老配置缺新增项时按带注释模板补齐一次（沿用现有取值），否则升级后服主看不到新开关。
            try {
                if (ServerProxyConfigFile.backfillMissingKeys()) {
                    logger.info("[zstdnet] added newly introduced options to {} (existing values kept).",
                        ServerProxyConfigFile.path());
                }
            } catch (IOException e) {
                logger.warn("[zstdnet] failed to backfill new config options: {}", e.toString());
            }
            return;
        }
        int listenPort = backend + 1;
        try {
            ServerProxyConfigFile.writePluginDefaults(listenPort, backend);
            logger.info("[zstdnet] generated default config: {}", ServerProxyConfigFile.path());
            logger.info("[zstdnet] listen=0.0.0.0:{} -> Velocity=127.0.0.1:{} (auto_takeover=false). Point ZstdNet mod"
                + " clients at port {}; vanilla players keep using {}.", listenPort, backend, listenPort, backend);
            if (!"127.0.0.1".equals(backendHost)) {
                // Velocity 绑在某个具体网卡地址上时，127.0.0.1 连不到它——提示服主改 target。
                logger.warn("[zstdnet] Velocity is bound to {}:{}, not a wildcard address. Set target={}:{} in {}.",
                    backendHost, backend, backendHost, backend, ServerProxyConfigFile.path());
            }
        } catch (IOException e) {
            logger.warn("[zstdnet] failed to write default config, falling back to runtime defaults: {}", e.toString());
        }
    }

    private static void tick() {
        try {
            if (RUNTIME.configChangedOnDisk()) {
                logger.info("[zstdnet] config changed on disk, reloading proxy.");
                RUNTIME.stop();
                RUNTIME.start(backendPort);
                logProxyState("reloaded");
            }
        } catch (RuntimeException e) {
            logger.warn("[zstdnet] config reload failed: {}", e.toString());
        }
    }

    private static void logProxyState(String action) {
        ServerProxyRuntime.HudSnapshot s = RUNTIME.hudSnapshot();
        if (RUNTIME.isRunning() && s != null) {
            logger.info("[zstdnet] proxy {}: listen {}:{} -> Velocity {}:{} (ZstdNet powered by minekuai.com).",
                action, s.listenHost(), s.listenPort(), backendHost, backendPort);
            logPortBanner(s);
        } else {
            logger.warn("[zstdnet] proxy did not start. Check listen/target in {} (the listen port must differ from"
                + " Velocity's bind port and be free).", ServerProxyConfigFile.path());
        }
    }

    /**
     * 启动（及配置热重载）后额外打印的端口说明横幅（双语，避免控制台中文乱码时读不懂）。
     */
    private static void logPortBanner(ServerProxyRuntime.HudSnapshot s) {
        logger.info("[zstdnet] ==================== ZstdNet ====================");
        logger.info("[zstdnet] 压缩代理已就绪 / compression proxy ready:");
        logger.info("[zstdnet]   正常端口 Normal port : {}   <- 未装 mod 的玩家直接连这个 (vanilla players, no mod)",
            backendPort);
        logger.info("[zstdnet]   代理端口 Proxy  port : {}   <- 装了 ZstdNet 客户端 mod 的玩家连这个 (mod clients; bind {})",
            s.listenPort(), s.listenHost());
        logger.info("[zstdnet] 两类玩家进入同一个 Velocity、同一组子服，可正常一起游玩。"
            + " (both kinds of players share the same network)");
        logger.info("[zstdnet] powered by minekuai.com");
        logger.info("[zstdnet] =================================================");
    }

    /**
     * {@code /zstdnet} 代理端管理命令。
     */
    public static final class Command implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "status" -> sendStatus(source);
                case "reload" -> {
                    RUNTIME.stop();
                    RUNTIME.start(backendPort);
                    source.sendMessage(RUNTIME.isRunning()
                        ? Component.text("[ZstdNet] proxy reloaded.", NamedTextColor.GREEN)
                        : Component.text("[ZstdNet] reload failed; check the config and console log.", NamedTextColor.RED));
                }
                case "start" -> {
                    if (RUNTIME.isRunning()) {
                        source.sendMessage(Component.text("[ZstdNet] proxy already running.", NamedTextColor.YELLOW));
                    } else {
                        RUNTIME.start(backendPort);
                        source.sendMessage(RUNTIME.isRunning()
                            ? Component.text("[ZstdNet] proxy started.", NamedTextColor.GREEN)
                            : Component.text("[ZstdNet] start failed; check the config and console log.", NamedTextColor.RED));
                    }
                }
                case "stop" -> {
                    RUNTIME.stop();
                    source.sendMessage(Component.text("[ZstdNet] proxy stopped.", NamedTextColor.GREEN));
                }
                default -> source.sendMessage(
                    Component.text("[ZstdNet] usage: /zstdnet <status|reload|start|stop>", NamedTextColor.YELLOW));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (args.length > 1) {
                return List.of();
            }
            return List.of("status", "reload", "start", "stop").stream()
                .filter(s -> s.startsWith(prefix))
                .toList();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission(ADMIN_PERMISSION);
        }
    }

    private static void sendStatus(CommandSource source) {
        if (!RUNTIME.isRunning()) {
            source.sendMessage(Component.text("[ZstdNet] proxy is NOT running. Velocity port=" + backendPort
                + ". Check " + ServerProxyConfigFile.path() + ".", NamedTextColor.YELLOW));
            return;
        }
        ServerProxyRuntime.HudSnapshot s = RUNTIME.hudSnapshot();
        if (s == null) {
            source.sendMessage(Component.text("[ZstdNet] proxy running (no traffic stats yet).", NamedTextColor.GREEN));
            return;
        }
        source.sendMessage(Component.text("[ZstdNet] running | listen " + s.listenHost() + ":" + s.listenPort()
            + " -> Velocity " + backendHost + ":" + backendPort, NamedTextColor.GREEN));
        source.sendMessage(Component.text("connections=" + s.connections()
            + " ratio=" + String.format(Locale.ROOT, "%.1f%%", s.ratioPercent())
            + " down zstd/raw=" + s.zstdDownRate() + "/" + s.rawDownRate() + " B/s", NamedTextColor.GRAY));
    }
}
