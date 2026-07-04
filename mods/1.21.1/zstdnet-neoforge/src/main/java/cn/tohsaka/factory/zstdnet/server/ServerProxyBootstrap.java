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

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import cn.tohsaka.factory.zstdnet.network.DictionarySync;
import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import cn.tohsaka.factory.zstdnet.network.VoicePortSync;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端引导模块。
 * <p>
 * 监听 Forge 服务器生命周期，在专用服启动/停止时控制内置 zstd 代理运行时。
 */
public final class ServerProxyBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerProxyBootstrap.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final ServerProxyRuntime RUNTIME = new ServerProxyRuntime();
    private static volatile int publishedLanPort = -1;
    private static volatile int activeLanPort = -1;
    private static volatile long lastHudSyncMillis;

    private ServerProxyBootstrap() {
    }

    /**
     * 注册服务端事件监听器（只注册一次）。
     */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerProxyBootstrap::onPlayerLoggedIn);
        LOGGER.info("zstdnet server bootstrap initialized");
    }

    /** 当前生效的语音端口计划（供 VoicePortSync 下发给客户端）。 */
    public static VoicePortPlan currentVoicePortPlan() {
        return RUNTIME.currentVoicePortPlan();
    }

    public static ServerHudSnapshot currentHudSnapshot() {
        ServerProxyRuntime.HudSnapshot snapshot = RUNTIME.hudSnapshot();
        if (snapshot == null) {
            return null;
        }
        return new ServerHudSnapshot(
            snapshot.modeName(),
            snapshot.listenHost(),
            snapshot.listenPort(),
            snapshot.rawBytes(),
            snapshot.zstdBytes(),
            snapshot.rawUpBytes(),
            snapshot.rawDownBytes(),
            snapshot.zstdUpBytes(),
            snapshot.zstdDownBytes(),
            snapshot.rawUpRate(),
            snapshot.rawDownRate(),
            snapshot.zstdUpRate(),
            snapshot.zstdDownRate(),
            snapshot.rawRate(),
            snapshot.zstdRate(),
            snapshot.ratioPercent(),
            snapshot.connections()
        );
    }

    /**
     * 专用服启动后启动代理运行时。
     */
    private static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        AutoPortPlan plan = DedicatedServerAutoPort.activePlan();
        if (plan != null) {
            LOGGER.info(
                "[zstdnet-server] public entry is {}:{}, backend was reassigned to {}:{}",
                plan.listenHost(),
                plan.listenPort(),
                plan.targetHost(),
                plan.targetPort()
            );
        }
        RUNTIME.start(event.getServer().getPort());
    }

    /**
     * 专用服停止前关闭代理运行时。
     */
    private static void onServerStopping(ServerStoppingEvent event) {
        publishedLanPort = -1;
        activeLanPort = -1;
        lastHudSyncMillis = 0L;
        RUNTIME.stop();
        LanBroadcaster.stop();
        DedicatedServerAutoPort.clear();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }

        if (server.isDedicatedServer()) {
            if (RUNTIME.configChangedOnDisk()) {
                LOGGER.info("[zstdnet-server] config changed on disk, reloading proxy.");
                RUNTIME.stop();
                RUNTIME.start(server.getPort());
            }
            long dictRolloutId = RUNTIME.consumeDictionaryRolloutId();
            if (dictRolloutId != 0L) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    DictionarySync.announce(player);
                }
                LOGGER.info("[zstdnet-server] dictionary_auto trained; rolled out to {} online player(s) now.", server.getPlayerList().getPlayerCount());
            }
            syncServerHudSnapshot(server);
            return;
        }

        boolean published = server.isPublished();
        int lanPort = published ? server.getPort() : -1;

        if (published && lanPort > 0) {
            forceDisableLanAuthentication(server);
            boolean lanCompression = ServerProxyConfigFile.readLanCompression();
            // configChangedOnDisk 依赖运行时已加载配置的时间戳；代理未启动时不可靠，
            // 故仅在运行中才用它触发热重载，避免默认(直连)路径每 tick 重入刷屏。
            boolean needsRefresh = publishedLanPort != lanPort
                || (RUNTIME.isRunning() && RUNTIME.configChangedOnDisk());
            if (needsRefresh) {
                publishedLanPort = lanPort;
                if (RUNTIME.isRunning()) {
                    LOGGER.info("[zstdnet-server] config/LAN state changed, reloading proxy.");
                    RUNTIME.stop();
                }
                if (lanCompression) {
                    LanBroadcaster.stop();
                    RUNTIME.startLan(lanPort);
                    activeLanPort = RUNTIME.isLanMode() ? lanPort : -1;
                    if (activeLanPort > 0) {
                        notifyLanProxyReady(server, lanPort);
                        LOGGER.info("[zstdnet-server] LAN world published on {}, zstd proxy armed (lan_compression=true).", lanPort);
                    } else {
                        LOGGER.warn("[zstdnet-server] LAN world published on {}, but zstd proxy did not start. Check zstdnet-server.properties.", lanPort);
                    }
                } else {
                    activeLanPort = -1;
                    LOGGER.info("[zstdnet-server] LAN world published on {} — direct connection, ZstdNet compression disabled (LAN default). Set lan_compression=true in zstdnet-server.properties to compress it for FRP/tunnel.", lanPort);
                    // 多网卡 LAN 广播：把原版 LAN ping 往每块网卡补发一遍，修「别的客户端在列表里搜不到」。
                    LanBroadcaster.start(server.getMotd(), lanPort);
                }
            }
            return;
        }

        if (RUNTIME.isLanMode()) {
            LOGGER.info("[zstdnet-server] LAN world is no longer published, stopping zstd proxy.");
            RUNTIME.stop();
        }
        LanBroadcaster.stop();
        publishedLanPort = -1;
        activeLanPort = -1;
    }

    private static void syncServerHudSnapshot(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastHudSyncMillis < 1000L) {
            return;
        }
        lastHudSyncMillis = now;

        ServerHudSnapshot snapshot = currentHudSnapshot();
        if (snapshot == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LanCompressionSync.sendServerHudSnapshot(player, snapshot);
        }
    }

    private static void notifyLanProxyReady(MinecraftServer server, int lanPort) {
        ServerProxyRuntime.HudSnapshot snapshot = RUNTIME.hudSnapshot();
        if (snapshot == null) {
            return;
        }
        Component message = Component.translatable(
            "zstdnet.singleplayer.lan_ready",
            copyableZstdPort(snapshot.listenPort()),
            lanPort
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
        LOGGER.info("[zstdnet-server] LAN ready: zstd listen port={}, game port={}", snapshot.listenPort(), lanPort);
    }

    private static Component copyableZstdPort(int port) {
        String text = String.valueOf(port);
        return Component.literal(text).withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("zstdnet.singleplayer.lan_ready.copy_zstd"))));
    }

    private static void forceDisableLanAuthentication(MinecraftServer server) {
        if (server.usesAuthentication()) {
            server.setUsesAuthentication(false);
            LOGGER.info("[zstdnet-server] LAN mode detected, disabled online authentication by default.");
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!RUNTIME.protectsBackendLogin()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        SocketAddress remoteAddress = player.connection.getRemoteAddress();
        if (isLoopback(remoteAddress) || ServerRealIpHooks.isForwardedConnection(player.connection.getConnection())) {
            if (activeLanPort > 0 && !player.connection.getConnection().isMemoryConnection()) {
                LanCompressionSync.requestCompressionUpgrade(player);
            }
            if (!player.connection.getConnection().isMemoryConnection()) {
                DictionarySync.announce(player);
                VoicePortSync.send(player);
            }
            return;
        }

        LOGGER.warn("[server] rejected direct backend login from {}", remoteAddress);
        player.connection.disconnect(Component.literal(ServerProxyRuntime.ZSTD_ADDRESS_HINT));
    }

    private static boolean isLoopback(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) {
            return false;
        }

        InetAddress ip = inet.getAddress();
        if (ip == null) {
            return false;
        }
        return ip.isLoopbackAddress() || ip.isAnyLocalAddress();
    }

    public record ServerHudSnapshot(
        String mode,
        String listenHost,
        int listenPort,
        long rawBytes,
        long zstdBytes,
        long rawUpBytes,
        long rawDownBytes,
        long zstdUpBytes,
        long zstdDownBytes,
        long rawUpRate,
        long rawDownRate,
        long zstdUpRate,
        long zstdDownRate,
        long rawRate,
        long zstdRate,
        double ratioPercent,
        int connections
    ) {
    }
}
