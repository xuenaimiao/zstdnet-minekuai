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
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
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
        MinecraftForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ServerProxyBootstrap::onPlayerLoggedIn);
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
    private static void onServerStarted(FMLServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        AutoPortPlan plan = DedicatedAutoPortState.activePlan();
        if (plan != null) {
            LOGGER.info(
                "[zstdnet-server] public entry is {}:{}, backend was reassigned to {}:{}",
                plan.listenHost(),
                plan.listenPort(),
                plan.targetHost(),
                plan.targetPort()
            );
        }
        RUNTIME.start(event.getServer().getServerPort());
    }

    /**
     * 专用服停止前关闭代理运行时。
     */
    private static void onServerStopping(FMLServerStoppingEvent event) {
        publishedLanPort = -1;
        activeLanPort = -1;
        lastHudSyncMillis = 0L;
        RUNTIME.stop();
        LanBroadcaster.stop();
        DedicatedAutoPortState.clear();
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        // 1.19.2 的 TickEvent.ServerTickEvent 没有 getServer()，改从生命周期钩子取当前服务器。
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        if (server.isDedicatedServer()) {
            if (RUNTIME.configChangedOnDisk()) {
                LOGGER.info("[zstdnet-server] config changed on disk, reloading proxy.");
                RUNTIME.stop();
                RUNTIME.start(server.getServerPort());
            }
            long dictRolloutId = RUNTIME.consumeDictionaryRolloutId();
            if (dictRolloutId != 0L) {
                for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
                    DictionarySync.announce(player);
                }
                LOGGER.info("[zstdnet-server] dictionary_auto trained; rolled out to {} online player(s) now.", server.getPlayerList().getCurrentPlayerCount());
            }
            syncServerHudSnapshot(server);
            return;
        }

        boolean published = server.getPublic();
        int lanPort = published ? server.getServerPort() : -1;

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
                    // 默认：局域网走原版直连，不起 zstd 代理（也就不会触发后端登录守卫）。
                    activeLanPort = -1;
                    LOGGER.info("[zstdnet-server] LAN world published on {} — direct connection, ZstdNet compression disabled (LAN default). Set lan_compression=true in zstdnet-server.properties to compress it for FRP/tunnel.", lanPort);
                    // 多网卡 LAN 广播：把原版 LAN ping 往每块网卡补发一遍，修「别的客户端在列表里搜不到」。
                    LanBroadcaster.start(server.getMOTD(), lanPort);
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
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            LanCompressionSync.sendServerHudSnapshot(player, snapshot);
        }
    }

    private static void notifyLanProxyReady(MinecraftServer server, int lanPort) {
        ServerProxyRuntime.HudSnapshot snapshot = RUNTIME.hudSnapshot();
        if (snapshot == null) {
            return;
        }
        ITextComponent message = new TranslationTextComponent(
            "zstdnet.singleplayer.lan_ready",
            copyableZstdPort(snapshot.listenPort()),
            lanPort
        );
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            player.sendMessage(message, Util.DUMMY_UUID);
        }
        LOGGER.info("[zstdnet-server] LAN ready: zstd listen port={}, game port={}", snapshot.listenPort(), lanPort);
    }

    private static ITextComponent copyableZstdPort(int port) {
        String text = String.valueOf(port);
        return new StringTextComponent(text).modifyStyle(style -> style
            .setFormatting(TextFormatting.AQUA)
            .setUnderlined(true)
            .setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
            .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TranslationTextComponent("zstdnet.singleplayer.lan_ready.copy_zstd"))));
    }

    private static void forceDisableLanAuthentication(MinecraftServer server) {
        if (server.isServerInOnlineMode()) {
            server.setOnlineMode(false);
            LOGGER.info("[zstdnet-server] LAN mode detected, disabled online authentication by default.");
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!RUNTIME.protectsBackendLogin()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();

        SocketAddress remoteAddress = player.connection.netManager.getRemoteAddress();
        if (isLoopback(remoteAddress) || ServerRealIpHooks.isForwardedConnection(player.connection.netManager)) {
            if (activeLanPort > 0 && !player.connection.netManager.isLocalChannel()) {
                LanCompressionSync.requestCompressionUpgrade(player);
            }
            if (!player.connection.netManager.isLocalChannel()) {
                DictionarySync.announce(player);
                VoicePortSync.send(player);
            }
            return;
        }

        LOGGER.warn("[server] rejected direct backend login from {}", remoteAddress);
        player.connection.disconnect(new StringTextComponent(ServerProxyRuntime.ZSTD_ADDRESS_HINT));
    }

    private static boolean isLoopback(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            return false;
        }
        InetSocketAddress inet = (InetSocketAddress) address;

        InetAddress ip = inet.getAddress();
        if (ip == null) {
            return false;
        }
        return ip.isLoopbackAddress() || ip.isAnyLocalAddress();
    }

    public static final class ServerHudSnapshot {
        private final String mode;
        private final String listenHost;
        private final int listenPort;
        private final long rawBytes;
        private final long zstdBytes;
        private final long rawUpBytes;
        private final long rawDownBytes;
        private final long zstdUpBytes;
        private final long zstdDownBytes;
        private final long rawUpRate;
        private final long rawDownRate;
        private final long zstdUpRate;
        private final long zstdDownRate;
        private final long rawRate;
        private final long zstdRate;
        private final double ratioPercent;
        private final int connections;

        public ServerHudSnapshot(
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
            this.mode = mode;
            this.listenHost = listenHost;
            this.listenPort = listenPort;
            this.rawBytes = rawBytes;
            this.zstdBytes = zstdBytes;
            this.rawUpBytes = rawUpBytes;
            this.rawDownBytes = rawDownBytes;
            this.zstdUpBytes = zstdUpBytes;
            this.zstdDownBytes = zstdDownBytes;
            this.rawUpRate = rawUpRate;
            this.rawDownRate = rawDownRate;
            this.zstdUpRate = zstdUpRate;
            this.zstdDownRate = zstdDownRate;
            this.rawRate = rawRate;
            this.zstdRate = zstdRate;
            this.ratioPercent = ratioPercent;
            this.connections = connections;
        }

        public String mode() {
            return mode;
        }

        public String listenHost() {
            return listenHost;
        }

        public int listenPort() {
            return listenPort;
        }

        public long rawBytes() {
            return rawBytes;
        }

        public long zstdBytes() {
            return zstdBytes;
        }

        public long rawUpBytes() {
            return rawUpBytes;
        }

        public long rawDownBytes() {
            return rawDownBytes;
        }

        public long zstdUpBytes() {
            return zstdUpBytes;
        }

        public long zstdDownBytes() {
            return zstdDownBytes;
        }

        public long rawUpRate() {
            return rawUpRate;
        }

        public long rawDownRate() {
            return rawDownRate;
        }

        public long zstdUpRate() {
            return zstdUpRate;
        }

        public long zstdDownRate() {
            return zstdDownRate;
        }

        public long rawRate() {
            return rawRate;
        }

        public long zstdRate() {
            return zstdRate;
        }

        public double ratioPercent() {
            return ratioPercent;
        }

        public int connections() {
            return connections;
        }
    }
}
