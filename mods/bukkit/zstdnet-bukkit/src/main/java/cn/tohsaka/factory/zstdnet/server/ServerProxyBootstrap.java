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

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 插件端引导：把 common 的 {@link ServerProxyRuntime} 接入 Bukkit 插件生命周期。
 * <p>
 * 本类与 {@code ServerProxyRuntime} 同包，因此可调用其包私有的 {@code start/stop/...} 方法
 * （与 Forge/Fabric 变体的 {@code ServerProxyBootstrap} 同理）。职责取专用服子集：
 * <ul>
 *   <li>{@code onEnable}：确保配置存在（缺失则写插件端默认，auto_takeover=false、独立监听端口）→ 启动代理；</li>
 *   <li>用独立守护线程周期轮询配置文件变更做热重载（不依赖服务端调度器，兼容 Folia 等多线程服务端）；</li>
 *   <li>{@code /zstdnet status|reload|start|stop} 管理命令。</li>
 * </ul>
 * 不做「拒绝原版直连后端」的逻辑——插件端 server-port 本就要给原版玩家直连。
 */
public final class ServerProxyBootstrap {

    private static final ServerProxyRuntime RUNTIME = new ServerProxyRuntime();
    /** 配置热重载轮询间隔（秒）。 */
    private static final long WATCH_INTERVAL_SECONDS = 5L;

    private static Plugin plugin;
    private static Logger logger;
    private static ScheduledExecutorService watcherExec;
    private static ScheduledFuture<?> watcher;
    private static volatile int backendPort;

    private ServerProxyBootstrap() {
    }

    /**
     * 插件 onEnable 时调用：准备配置、启动代理、注册配置热重载轮询。
     */
    public static void initBukkit(Plugin pl) {
        plugin = pl;
        logger = pl.getLogger();
        backendPort = Bukkit.getServer().getPort();

        ensureConfig(backendPort);
        RUNTIME.start(backendPort);
        logProxyState("started");

        // 用独立守护线程轮询配置磁盘变更：代理 start/stop 自带 lifecycleLock，tick() 只触碰 RUNTIME 与 logger、
        // 不触碰任何 Bukkit API，因此与服务端线程模型无关——Spigot/Paper/Purpur/Folia/混合端 全部通用，
        // 不依赖 Bukkit 调度器（Folia 已移除旧调度器的同步方法）。
        watcherExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zstdnet-bukkit-config-watcher");
            t.setDaemon(true);
            return t;
        });
        watcher = watcherExec.scheduleWithFixedDelay(
            ServerProxyBootstrap::tick, WATCH_INTERVAL_SECONDS, WATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 当前生效的语音端口计划（供插件端 {@code network.VoicePortSync} 下发给 ZstdNet 客户端 mod）。
     * 代理未启动时返回空计划。
     */
    public static VoicePortPlan currentVoicePortPlan() {
        return RUNTIME.currentVoicePortPlan();
    }

    /**
     * 插件 onDisable 时调用：停止轮询并关闭代理。
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

    private static void ensureConfig(int backend) {
        if (Files.exists(ServerProxyConfigFile.path())) {
            return;
        }
        int listenPort = backend + 1;
        try {
            ServerProxyConfigFile.writePluginDefaults(listenPort, backend);
            logger.info("[zstdnet] generated default config: " + ServerProxyConfigFile.path());
            logger.info("[zstdnet] listen=0.0.0.0:" + listenPort + " -> backend=127.0.0.1:" + backend
                + " (auto_takeover=false). Point ZstdNet mod clients at port " + listenPort
                + "; vanilla players keep using " + backend + ".");
        } catch (IOException e) {
            logger.warning("[zstdnet] failed to write default config, falling back to runtime defaults: " + e);
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
            logger.warning("[zstdnet] config reload failed: " + e);
        }
    }

    private static void logProxyState(String action) {
        ServerProxyRuntime.HudSnapshot s = RUNTIME.hudSnapshot();
        if (RUNTIME.isRunning() && s != null) {
            logger.info("[zstdnet] proxy " + action + ": listen " + s.listenHost() + ":" + s.listenPort()
                + " -> backend 127.0.0.1:" + backendPort + " (ZstdNet powered by minekuai.com).");
            // 额外打印一段「两端口说明」横幅：明确告诉服主哪个是给普通玩家的正常端口、哪个是给装了
            // ZstdNet 客户端 mod 的玩家的压缩代理端口（双语，避免控制台中文乱码时也能读懂端口信息）。
            logPortBanner(s);
        } else {
            logger.warning("[zstdnet] proxy did not start. Check listen/target in " + ServerProxyConfigFile.path()
                + " (the listen port must differ from the Minecraft server-port and be free).");
        }
    }

    /**
     * 服务器启动（及配置热重载）后额外打印的端口说明横幅。
     * <p>「正常端口」= MC 后端 server-port，未装 mod 的原版玩家直连；
     * 「代理端口」= 本插件的 zstd 监听端口，装了 ZstdNet 客户端 mod 的玩家连这个即可获得压缩。
     * 两类玩家进入同一个后端、同一个世界，可正常一起游玩。
     */
    private static void logPortBanner(ServerProxyRuntime.HudSnapshot s) {
        logger.info("[zstdnet] ==================== ZstdNet ====================");
        logger.info("[zstdnet] 压缩代理已就绪 / compression proxy ready:");
        logger.info("[zstdnet]   正常端口 Normal port : " + backendPort
            + "   <- 未装 mod 的玩家直接连这个 (vanilla players, no mod)");
        logger.info("[zstdnet]   代理端口 Proxy  port : " + s.listenPort()
            + "   <- 装了 ZstdNet 客户端 mod 的玩家连这个 (mod clients; bind " + s.listenHost() + ")");
        logger.info("[zstdnet] 两类玩家进入同一个后端、同一个世界，可正常一起游玩。"
            + " (both kinds of players share the same world)");
        logger.info("[zstdnet] 提示: 要让 mod 玩家真正压缩省流量，后端需 online-mode=false"
            + " (插件端不做正版验证 / plugin cannot verify premium accounts)。");
        logger.info("[zstdnet] powered by minekuai.com");
        logger.info("[zstdnet] =================================================");
    }

    /**
     * 处理 {@code /zstdnet} 管理命令（由插件主类的 onCommand 转发）。
     */
    public static boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zstdnet.admin")) {
            sender.sendMessage("§cYou don't have permission to manage ZstdNet.");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sendStatus(sender);
            case "reload" -> {
                RUNTIME.stop();
                RUNTIME.start(backendPort);
                sender.sendMessage(RUNTIME.isRunning()
                    ? "§a[ZstdNet] proxy reloaded."
                    : "§c[ZstdNet] reload failed; check the config and console log.");
            }
            case "start" -> {
                if (RUNTIME.isRunning()) {
                    sender.sendMessage("§e[ZstdNet] proxy already running.");
                } else {
                    RUNTIME.start(backendPort);
                    sender.sendMessage(RUNTIME.isRunning()
                        ? "§a[ZstdNet] proxy started."
                        : "§c[ZstdNet] start failed; check the config and console log.");
                }
            }
            case "stop" -> {
                RUNTIME.stop();
                sender.sendMessage("§a[ZstdNet] proxy stopped.");
            }
            default -> sender.sendMessage("§e[ZstdNet] usage: /zstdnet <status|reload|start|stop>");
        }
        return true;
    }

    private static void sendStatus(CommandSender sender) {
        if (!RUNTIME.isRunning()) {
            sender.sendMessage("§e[ZstdNet] proxy is NOT running. backend server-port=" + backendPort
                + ". Check " + ServerProxyConfigFile.path() + ".");
            return;
        }
        ServerProxyRuntime.HudSnapshot s = RUNTIME.hudSnapshot();
        if (s == null) {
            sender.sendMessage("§a[ZstdNet] proxy running (no traffic stats yet).");
            return;
        }
        sender.sendMessage("§a[ZstdNet] running §7| listen §f" + s.listenHost() + ":" + s.listenPort()
            + " §7-> backend §f127.0.0.1:" + backendPort);
        sender.sendMessage("§7connections=§f" + s.connections()
            + " §7ratio=§f" + String.format(Locale.ROOT, "%.1f%%", s.ratioPercent())
            + " §7down zstd/raw=§f" + s.zstdDownRate() + "/" + s.rawDownRate() + " B/s");
    }
}
