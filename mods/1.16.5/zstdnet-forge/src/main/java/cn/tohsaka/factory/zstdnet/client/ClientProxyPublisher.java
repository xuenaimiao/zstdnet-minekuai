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

package cn.tohsaka.factory.zstdnet.client;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.coremod.ConnectScreenHooks;
import cn.tohsaka.factory.zstdnet.core.compress.ClientDictionaryStore;
import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.Branding;
import cn.tohsaka.factory.zstdnet.proxy.ConnectTargets;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import cn.tohsaka.factory.zstdnet.proxy.RawFallbackNotice;
import cn.tohsaka.factory.zstdnet.proxy.ZstdProbe;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.ServerListScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.network.LanServerInfo;
import net.minecraft.util.Util;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * Client-side runtime that keeps vanilla server entries untouched and only swaps
 * the actual connect target to a temporary local zstd proxy when the player joins.
 */
public final class ClientProxyPublisher {
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int DEFAULT_BACKEND_PORT = 25566;
    private static final int PORT_TEXT_NORMAL = 14737632;
    private static final int PORT_TEXT_INVALID = 16733525;
    private static final int HUD_SERVER_BACKGROUND = 0x90241208;
    private static final int HUD_SERVER_TITLE = 0xFFE1A3;
    private static final int HUD_SERVER_TEXT = 0xFFD08A;
    private static final int HUD_CLIENT_BACKGROUND = 0x90081224;
    private static final int HUD_CLIENT_TITLE = 0xA8E6FF;
    private static final int HUD_CLIENT_TEXT = 0x8FD3FF;
    private static final long REMOTE_HUD_SNAPSHOT_TTL_MS = 3000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientProxyPublisher.class);
    private static final ClientProxyPublisher INSTANCE = new ClientProxyPublisher();
    private static final ITextComponent BACKEND_PORT_LABEL = new TranslationTextComponent("zstdnet.share_to_lan.backend_port");
    private static final ITextComponent ZSTD_PORT_LABEL = new TranslationTextComponent("zstdnet.share_to_lan.zstd_port");
    private static final ITextComponent ZSTD_PORT_INVALID = new TranslationTextComponent("zstdnet.share_to_lan.port_invalid");
    private static final ITextComponent ZSTD_PORT_UNAVAILABLE = new TranslationTextComponent("zstdnet.share_to_lan.port_unavailable");

    private final Object stateLock = new Object();
    private final Map<MultiplayerScreen, JoinScreenState> joinScreens = new WeakHashMap<>();
    private final Map<ServerListScreen, DirectJoinState> directJoinScreens = new WeakHashMap<>();
    private final Map<Screen, ShareToLanState> shareToLanScreens = new WeakHashMap<>();

    private LocalZstdNet.ProxyHandle activeProxy;
    private LocalZstdNet.ProxyHandle activeSession;
    private boolean hudVisible = false;
    private static final long HUD_FORMAT_INTERVAL_MS = 250L;
    private static final String[] SIZE_UNITS = {"KB", "MB", "GB", "TB"};
    private long lastHudFormatMs;
    private String[] cachedHostLines;
    private String[] cachedClientLines;
    private boolean singleplayerLanHintShown;
    private Object lastListEntry;
    private long lastListClickTime;
    private ServerProxyBootstrap.ServerHudSnapshot remoteServerHudSnapshot;
    private long remoteServerHudSnapshotMillis;
    private String pendingVoiceTransport;
    private List<Integer> pendingVoicePorts;

    private ClientProxyPublisher() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "zstdnet-client-shutdown"));
    }

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenInit);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onMousePressed);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenOpening);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onClientLogin);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onClientLogout);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenRenderPre);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenRender);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onRegisterClientCommands);
        LOGGER.info("zstdnet client runtime initialized");
    }

    public static void acceptRemoteServerHudSnapshot(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        INSTANCE.updateRemoteServerHudSnapshot(snapshot);
    }

    /** 收到服务端下发的语音端口列表后，在本机本地代理上为这些端口开监听（见 VoicePortSync）。 */
    public static void acceptVoicePortList(String transport, List<Integer> ports) {
        INSTANCE.applyVoicePortList(transport, ports);
    }

    private void onScreenInit(GuiScreenEvent.InitGuiEvent.Post event) {
        Screen screen = event.getGui();
        // 1.16.5 的 InitGuiEvent 只暴露 getWidgetList()（仅按钮）；ServerSelectionList 不在按钮列表里，
        // 故统一从 screen.getEventListeners()（完整 children）取，既含按钮也含列表与输入框。
        List<?> listeners = screen.getEventListeners();

        if (screen instanceof MultiplayerScreen) {
            MultiplayerScreen joinScreen = (MultiplayerScreen) screen;
            JoinScreenState state = JoinScreenState.from(listeners);
            synchronized (stateLock) {
                joinScreens.put(joinScreen, state);
            }
            LOGGER.debug("zstdnet: hooked multiplayer screen");
            return;
        }

        if (screen instanceof ServerListScreen) {
            ServerListScreen directJoinScreen = (ServerListScreen) screen;
            DirectJoinState state = DirectJoinState.from(listeners);
            synchronized (stateLock) {
                directJoinScreens.put(directJoinScreen, state);
            }
            LOGGER.debug("zstdnet: hooked direct-join screen");
            return;
        }

        if (isShareToLanLikeScreen(screen)) {
            ShareToLanState state = attachShareToLanState(screen, event, listeners);
            if (state != null) {
                synchronized (stateLock) {
                    shareToLanScreens.put(screen, state);
                }
                LOGGER.debug("zstdnet: hooked share-to-lan screen {}", screen.getClass().getName());
            }
        }
    }

    // 1.16.5 没有 ScreenEvent.Closing；joinScreens/directJoinScreens/shareToLanScreens
    // 均为 WeakHashMap，屏幕关闭被 GC 回收后条目自动清除，因此无需 onScreenClosing。

    private void onScreenOpening(GuiOpenEvent event) {
        // 1.16.5 的 GuiOpenEvent 只暴露「即将打开的屏幕」，没有 getCurrentScreen()；
        // 当前屏幕从 Minecraft.currentScreen 取（此时尚未切换，仍是旧屏幕）。
        Screen newScreen = event.getGui();
        Screen currentScreen = Minecraft.getInstance().currentScreen;
        if (newScreen instanceof ConnectingScreen) {
            adoptInterceptedProxy();
        }
        if (currentScreen instanceof ConnectingScreen && newScreen != null) {
            releaseActiveProxyListener();
        }
    }

    private void onClientLogin(ClientPlayerNetworkEvent.LoggedInEvent event) {
        synchronized (stateLock) {
            adoptInterceptedProxyLocked();
            if (activeProxy != null) {
                activeSession = activeProxy;
            }
            if (activeSession != null && pendingVoicePorts != null) {
                activeSession.armVoicePorts(pendingVoiceTransport, pendingVoicePorts);
            }
        }
    }

    private void onClientLogout(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        synchronized (stateLock) {
            closeActiveSessionLocked();
            remoteServerHudSnapshot = null;
            remoteServerHudSnapshotMillis = 0L;
            pendingVoiceTransport = null;
            pendingVoicePorts = null;
        }
    }

    private void applyVoicePortList(String transport, List<Integer> ports) {
        synchronized (stateLock) {
            pendingVoiceTransport = transport;
            pendingVoicePorts = ports;
            LocalZstdNet.ProxyHandle session = activeSession != null ? activeSession : activeProxy;
            if (session != null) {
                session.armVoicePorts(transport, ports);
            }
        }
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        maybeShowRawFallbackNotice(minecraft);
        boolean inSingleplayerWorld = minecraft.player != null && minecraft.getIntegratedServer() != null;
        if (!inSingleplayerWorld) {
            singleplayerLanHintShown = false;
            return;
        }
        if (singleplayerLanHintShown) {
            return;
        }

        sendClientMessage(new TranslationTextComponent("zstdnet.singleplayer.lan_hint"));
        sendClientMessage(new TranslationTextComponent("zstdnet.singleplayer.lan_command_hint"));
        singleplayerLanHintShown = true;
    }

    /** raw_fallback 直连进服后，把「服务器没有 ZstdNet、本次未压缩」的提示打进聊天栏（每次连接只打一次）。 */
    private void maybeShowRawFallbackNotice(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getCurrentServerData() == null) {
            return;
        }
        RawFallbackNotice.Notice notice = RawFallbackNotice.take();
        if (notice == null) {
            return;
        }
        sendClientMessage(new TranslationTextComponent(
            notice.knownRelay() ? "zstdnet.fallback.relay_notice" : "zstdnet.fallback.notice",
            notice.address()
        ));
        sendClientMessage(new TranslationTextComponent("zstdnet.fallback.hint"));
    }

    private void onRenderGui(RenderGameOverlayEvent.Post event) {
        // 1.16.5 的 RenderGameOverlayEvent.Post 会按 ElementType 多次触发；只在 ALL（全部 HUD 渲染完成后）渲染一次。
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameSettings.hideGUI) {
            return;
        }
        boolean remoteServer = minecraft.getCurrentServerData() != null;
        if (!remoteServer) {
            synchronized (stateLock) {
                closeActiveSessionLocked();
                closeActiveProxyLocked();
            }
        }

        LocalZstdNet.ProxyHandle session;
        synchronized (stateLock) {
            if (!hudVisible) {
                return;
            }
            session = remoteServer ? activeSession : null;
        }
        // HUD 隐藏时已在上方提前返回；放到此处再取主机快照，连服且 HUD 关闭时省去每帧的快照与同步开销。
        ServerProxyBootstrap.ServerHudSnapshot hostSnapshot = visibleHostSnapshot();
        if (session == null && hostSnapshot == null) {
            return;
        }
        // 统计每 ~500ms 才更新一次：HUD 文案按 HUD_FORMAT_INTERVAL_MS 节流重建，避免每帧 I18n.format + String.format + 数组分配。
        long hudNow = System.currentTimeMillis();
        boolean hudRefresh = (hudNow - lastHudFormatMs) >= HUD_FORMAT_INTERVAL_MS;
        if (hudRefresh) {
            lastHudFormatMs = hudNow;
        }
        MatrixStack gui = event.getMatrixStack();
        int y = 8;

        y = renderHudPanel(gui, minecraft, y, Branding.HUD_LINES, 0x90123A12, 0xFFE066, 0xC8F0A0);

        if (hostSnapshot != null) {
            if (hudRefresh || cachedHostLines == null) {
                String hostMode = translateServerHudMode(hostSnapshot.mode());
                cachedHostLines = new String[]{
                    I18n.format("zstdnet.hud.host.title", hostMode, hostSnapshot.listenHost(), hostSnapshot.listenPort()),
                    I18n.format("zstdnet.hud.server.zstd_rate", formatRate(hostSnapshot.zstdDownRate()), formatRate(hostSnapshot.zstdUpRate())),
                    I18n.format("zstdnet.hud.server.raw_rate", formatRate(hostSnapshot.rawDownRate()), formatRate(hostSnapshot.rawUpRate())),
                    I18n.format("zstdnet.hud.server.zstd_total", formatSize(hostSnapshot.zstdBytes())),
                    I18n.format("zstdnet.hud.server.raw_total", formatSize(hostSnapshot.rawBytes())),
                    I18n.format("zstdnet.hud.server.ratio", formatPercent(hostSnapshot.ratioPercent())),
                    I18n.format("zstdnet.hud.connections", hostSnapshot.connections())
                };
            }
            y = renderHudPanel(gui, minecraft, y, cachedHostLines, HUD_SERVER_BACKGROUND, HUD_SERVER_TITLE, HUD_SERVER_TEXT);
        } else {
            cachedHostLines = null;
        }

        if (session != null) {
            if (hudRefresh || cachedClientLines == null) {
                LocalZstdNet.StatsSnapshot stats = session.statsSnapshot();
                String clientMode = translateClientHudMode(stats.mode());
                java.util.List<String> clientLines = new java.util.ArrayList<>(5);
                clientLines.add(I18n.format("zstdnet.hud.client.title", clientMode, stats.remoteHost(), stats.remotePort()));
                clientLines.add(I18n.format("zstdnet.hud.client.wire", formatRate(stats.wireUpRate()), formatRate(stats.wireDownRate())));
                clientLines.add(I18n.format("zstdnet.hud.client.raw", formatRate(stats.rawUpRate()), formatRate(stats.rawDownRate())));
                clientLines.add(I18n.format(
                    "zstdnet.hud.total_ratio",
                    formatSize(stats.wireUpBytes() + stats.wireDownBytes()),
                    formatSize(stats.rawUpBytes() + stats.rawDownBytes()),
                    formatPercent(stats.ratioPercent())
                ));
                if (stats.hasCacheActivity()) {
                    clientLines.add(I18n.format("zstdnet.hud.client.cache",
                        stats.cacheRefHits(), stats.cacheWarmHits(), stats.cachePatchHits(),
                        formatSize(stats.cacheSavedBytes())));
                }
                cachedClientLines = clientLines.toArray(new String[0]);
            }
            renderHudPanel(gui, minecraft, y, cachedClientLines, HUD_CLIENT_BACKGROUND, HUD_CLIENT_TITLE, HUD_CLIENT_TEXT);
        } else {
            cachedClientLines = null;
        }
    }

    private void onScreenRender(GuiScreenEvent.DrawScreenEvent.Post event) {
        Screen shareToLanScreen = event.getGui();
        if (!isShareToLanLikeScreen(shareToLanScreen)) {
            return;
        }

        ShareToLanState state = getShareToLanState(shareToLanScreen);
        if (state == null) {
            return;
        }

        MatrixStack poseStack = event.getMatrixStack();
        int labelX = state.zstdPortEdit.x;
        int labelY = state.zstdPortEdit.y - 10;
        Minecraft.getInstance().fontRenderer.drawTextWithShadow(poseStack, ZSTD_PORT_LABEL, labelX, labelY, 0xFFFFFF);
    }

    private void onScreenRenderPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        Screen screen = event.getGui();
        if (isShareToLanLikeScreen(screen)) {
            ensureShareToLanWidgetAttached(screen);
        }
    }

    private void ensureShareToLanWidgetAttached(Screen shareToLanScreen) {
        ShareToLanState state = getShareToLanState(shareToLanScreen);
        if (state != null) {
            ensureShareToLanWidgetAttached(shareToLanScreen, state);
        }
    }

    // 1.16.5 没有 RegisterClientCommandsEvent；改用 RegisterCommandsEvent。因本类仅在客户端 dist 初始化，
    // 该监听只在集成服/单人/局域网主机启动时触发，故 /zstdhud /zstdport 仅在这些场景注册——首轮可接受的限制。
    private void onRegisterClientCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("zstdhud")
                .executes(this::showHudStatus)
                .then(Commands.literal("on").executes(context -> setHudVisible(context, true)))
                .then(Commands.literal("off").executes(context -> setHudVisible(context, false)))
                .then(Commands.literal("toggle").executes(this::toggleHudVisible))
        );
        event.getDispatcher().register(buildPortCommand("zstdport"));
    }

    private LiteralArgumentBuilder<CommandSource> buildPortCommand(String literal) {
        return Commands.literal(literal)
            .executes(this::showPortStatus)
            .then(Commands.literal("show").executes(this::showPortStatus))
            .then(
                Commands.literal("zstd")
                    .requires(source -> canEditPortConfig())
                    .then(
                        Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                            .executes(this::setZstdPort)
                    )
            )
            .then(
                Commands.literal("game")
                    .requires(source -> canEditPortConfig())
                    .then(
                        Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                            .executes(this::setGamePort)
                    )
            )
            .then(buildVoiceTargetBranch("voice"))
            .then(buildVoiceListenBranch("zstdvoice"));
    }

    private LiteralArgumentBuilder<CommandSource> buildVoiceTargetBranch(String literal) {
        return Commands.literal(literal)
            .requires(source -> canEditPortConfig())
            .executes(this::showVoicePortStatus)
            .then(Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                .executes(this::setVoicePort));
    }

    private LiteralArgumentBuilder<CommandSource> buildVoiceListenBranch(String literal) {
        return Commands.literal(literal)
            .requires(source -> canEditPortConfig())
            .executes(this::showVoicePortStatus)
            .then(Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                .executes(this::setVoiceListenPort));
    }

    private void onKeyPressed(GuiScreenEvent.KeyboardKeyPressedEvent.Pre event) {
        Screen screen = event.getGui();

        if (screen instanceof MultiplayerScreen) {
            MultiplayerScreen joinScreen = (MultiplayerScreen) screen;
            if (event.getKeyCode() != 257 && event.getKeyCode() != 335) {
                return;
            }
            if (connectSelected(joinScreen)) {
                event.setCanceled(true);
            }
            return;
        }

        if (screen instanceof ServerListScreen) {
            ServerListScreen directJoinScreen = (ServerListScreen) screen;
            if (event.getKeyCode() != 257 && event.getKeyCode() != 335) {
                return;
            }
            DirectJoinState state = getDirectJoinState(directJoinScreen);
            if (state == null || state.ipEdit == null || state.selectButton == null || !state.selectButton.active) {
                return;
            }
            if (screen.getListener() != state.ipEdit) {
                return;
            }
            if (connectDirect(directJoinScreen, state)) {
                event.setCanceled(true);
            }
            return;
        }

        if (isShareToLanLikeScreen(screen)) {
            if (event.getKeyCode() != 257 && event.getKeyCode() != 335) {
                return;
            }
            ShareToLanState state = getShareToLanState(screen);
            if (state == null || !state.vanillaStartButton.active) {
                return;
            }
            if (prepareLanWorldPublish(state)) {
                state.vanillaStartButton.onPress();
            }
            event.setCanceled(true);
        }
    }

    private void onMousePressed(GuiScreenEvent.MouseClickedEvent.Pre event) {
        if (event.getButton() != 0) {
            return;
        }

        Screen screen = event.getGui();

        if (screen instanceof MultiplayerScreen) {
            MultiplayerScreen joinScreen = (MultiplayerScreen) screen;
            JoinScreenState state = getJoinScreenState(joinScreen);
            if (state == null) {
                return;
            }

            if (state.selectButton != null && state.selectButton.active && state.selectButton.isMouseOver(event.getMouseX(), event.getMouseY())) {
                if (connectSelected(joinScreen)) {
                    event.setCanceled(true);
                }
                return;
            }

            if (state.serverList == null || !state.serverList.isMouseOver(event.getMouseX(), event.getMouseY())) {
                return;
            }

            ServerSelectionList.Entry entry = hoveredEntry(state.serverList, event.getMouseX(), event.getMouseY());
            if (entry == null) {
                return;
            }

            long now = System.currentTimeMillis();
            boolean isDoubleClick = entry == lastListEntry && (now - lastListClickTime) < 250L;
            double rowOffsetX = event.getMouseX() - state.serverList.getRowLeft();
            boolean isJoinIconClick = entry instanceof ServerSelectionList.NormalEntry
                && rowOffsetX > 16.0D
                && rowOffsetX < 32.0D;
            lastListEntry = entry;
            lastListClickTime = now;

            if (!isDoubleClick && !isJoinIconClick) {
                return;
            }

            if (connectEntry(joinScreen, entry)) {
                event.setCanceled(true);
            }
            return;
        }

        if (screen instanceof ServerListScreen) {
            ServerListScreen directJoinScreen = (ServerListScreen) screen;
            DirectJoinState state = getDirectJoinState(directJoinScreen);
            if (state == null || state.selectButton == null || !state.selectButton.active) {
                return;
            }
            if (state.selectButton.isMouseOver(event.getMouseX(), event.getMouseY()) && connectDirect(directJoinScreen, state)) {
                event.setCanceled(true);
            }
            return;
        }

        if (isShareToLanLikeScreen(screen)) {
            ShareToLanState state = getShareToLanState(screen);
            if (state == null || !state.vanillaStartButton.active) {
                return;
            }
            if (state.vanillaStartButton.isMouseOver(event.getMouseX(), event.getMouseY())) {
                if (!prepareLanWorldPublish(state)) {
                    event.setCanceled(true);
                }
            }
        }
    }

    private boolean connectSelected(MultiplayerScreen screen) {
        JoinScreenState state = getJoinScreenState(screen);
        if (state == null || state.serverList == null) {
            return false;
        }
        ServerSelectionList.Entry entry = state.serverList.getSelected();
        return connectEntry(screen, entry);
    }

    private boolean connectEntry(Screen parent, ServerSelectionList.Entry entry) {
        if (entry instanceof ServerSelectionList.NormalEntry) {
            ServerSelectionList.NormalEntry onlineEntry = (ServerSelectionList.NormalEntry) entry;
            return connect(parent, onlineEntry.getServerData());
        }
        if (entry instanceof ServerSelectionList.LanDetectedEntry) {
            ServerSelectionList.LanDetectedEntry networkEntry = (ServerSelectionList.LanDetectedEntry) entry;
            LanServerInfo lanServer = networkEntry.getServerData();
            return connect(parent, new ServerData(lanServer.getServerMotd(), lanServer.getServerIpPort(), true));
        }
        return false;
    }

    private boolean connectDirect(ServerListScreen screen, DirectJoinState state) {
        if (state.ipEdit == null) {
            return false;
        }
        String raw = normalizeAddress(state.ipEdit.getText());
        if (raw.isEmpty() || !isValidServerAddress(raw)) {
            return false;
        }
        return connect(screen, resolveDirectJoinServer(raw));
    }

    private boolean connect(Screen parent, ServerData serverData) {
        String remoteAddr = normalizeAddress(serverData.serverIP);
        if (remoteAddr.isEmpty() || !isValidServerAddress(remoteAddr)) {
            LOGGER.warn("zstdnet: invalid remote address {}", serverData.serverIP);
            return false;
        }

        RemoteTarget remote = resolveRemoteTarget(remoteAddr);
        if (remote == null) {
            LOGGER.warn("zstdnet: failed to resolve remote target {}", remoteAddr);
            return false;
        }

        // 新的连接决策开始：清掉上一次连接可能残留的回退提示，避免串到本次服务器。
        RawFallbackNotice.clear();

        // 局域网/本机/私网目标：默认直连，不起压缩代理；仅 compress_lan 显式开启时才压缩。
        if (remote.directLan() && !ClientConfig.compressLan()) {
            serverData.serverIP = remoteAddr;
            LOGGER.info("zstdnet: LAN/loopback target {} -> direct connection (compression off)", remoteAddr);
            ConnectScreenHooks.setBypass(true);
            // 1.16.5 无静态 ConnectScreen.startConnecting；ConnectingScreen(parent,mc,serverData)
            // 会 setServerData 并读取 serverData.serverIP 发起连接。
            Minecraft mc = Minecraft.getInstance();
            mc.displayGuiScreen(new ConnectingScreen(parent, mc, serverData));
            return true;
        }

        // 回退兼容（raw_fallback，默认开）：接管前先探测服务端是否真的会说 ZSTD。樱花frp 等联机映射
        // 把「原版局域网端口」映到公网时，目标端口后面并没有 ZstdNet 服务端，强制 ZSTD 只会把玩家挡在门外。
        // 探测确认对端不说 ZSTD（NO_ZSTD）→ 改用原版直连进入，进服后聊天栏提示；
        // 探测连不上（UNREACHABLE，服务器可能离线）→ 仍走 ZSTD 代理，保留既有的登录期友好报错。
        if (ClientConfig.rawFallback()
            && ZstdProbe.probe(remote.connectHost(), remote.connectPort()) == ZstdProbe.Result.NO_ZSTD) {
            boolean knownRelay = ConnectTargets.isKnownRelayHost(LocalZstdNet.HostPort.parse(remoteAddr).host())
                || ConnectTargets.isKnownRelayHost(remote.connectHost());
            serverData.serverIP = remoteAddr;
            LOGGER.info("zstdnet: {} does not speak ZSTD -> vanilla direct connection (knownRelay={})", remoteAddr, knownRelay);
            RawFallbackNotice.arm(remoteAddr, knownRelay);
            ConnectScreenHooks.setBypass(true);
            Minecraft mc = Minecraft.getInstance();
            mc.displayGuiScreen(new ConnectingScreen(parent, mc, serverData));
            return true;
        }

        LocalZstdNet.ProxyHandle proxy;

        try {
            synchronized (stateLock) {
                closeActiveProxyLocked();
                closeActiveSessionLocked();
                CompressionOptions compression = ClientDictionaryStore.resolveFor(
                    Platforms.get().configDir(), remoteAddr, ClientConfig.compression());
                LocalZstdNet.configureClientTransform(ClientConfig.transform());
                LocalZstdNet.configureClientCache(ClientConfig.cacheEnabled(), ClientConfig.cachePersist(), ClientConfig.cachePersistBytes());
                proxy = LocalZstdNet.start(
                    remote.connectHost(),
                    remote.connectPort(),
                    remote.connectHost(),
                    remote.connectPort(),
                    remote.presentedHost(),
                    remote.presentedPort(),
                    ClientConfig.getLevel(),
                    compression,
                    LocalZstdNet.Mode.ZSTD
                );
                activeProxy = proxy;
            }
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to start local proxy for {}", remoteAddr, e);
            return false;
        }

        serverData.serverIP = remoteAddr;
        String localAddr = "127.0.0.1:" + proxy.localPort();
        LOGGER.info("zstdnet: {} -> {} via local {}", safe(serverData.serverName), remoteAddr, localAddr);
        ConnectScreenHooks.setBypass(true);
        // 服务器列表条目保留真实远端 IP，但实际连接走本地代理：手动 setServerData（供 HUD/当前服判定），
        // 再用 (parent,mc,ip,port) 构造器连到本地代理地址。
        Minecraft mc = Minecraft.getInstance();
        mc.setServerData(serverData);
        ServerAddress localTarget = ServerAddress.fromString(localAddr);
        mc.displayGuiScreen(new ConnectingScreen(parent, mc, localTarget.getIP(), localTarget.getPort()));
        return true;
    }

    private JoinScreenState getJoinScreenState(MultiplayerScreen screen) {
        synchronized (stateLock) {
            return joinScreens.get(screen);
        }
    }

    private DirectJoinState getDirectJoinState(ServerListScreen screen) {
        synchronized (stateLock) {
            return directJoinScreens.get(screen);
        }
    }

    private ShareToLanState getShareToLanState(Screen screen) {
        synchronized (stateLock) {
            return shareToLanScreens.get(screen);
        }
    }

    private ServerSelectionList.Entry hoveredEntry(ServerSelectionList list, double mouseX, double mouseY) {
        for (ServerSelectionList.Entry entry : list.getEventListeners()) {
            if (entry != null && entry.isMouseOver(mouseX, mouseY)) {
                return entry;
            }
        }
        return null;
    }

    private void closeActiveProxy() {
        synchronized (stateLock) {
            closeActiveProxyLocked();
        }
    }

    private void adoptInterceptedProxy() {
        synchronized (stateLock) {
            adoptInterceptedProxyLocked();
        }
    }

    private void adoptInterceptedProxyLocked() {
        LocalZstdNet.ProxyHandle intercepted = ConnectScreenHooks.takeProxy();
        if (intercepted == null) {
            return;
        }

        closeActiveProxyLocked();
        closeActiveSessionLocked();
        activeProxy = intercepted;
        LOGGER.info("zstdnet: adopted intercepted programmatic proxy on local {}", intercepted.localPort());
    }

    private void releaseActiveProxyListener() {
        synchronized (stateLock) {
            if (activeProxy == null) {
                return;
            }
            try {
                LOGGER.info(
                    "zstdnet: releasing local proxy TCP listener on 127.0.0.1:{} while keeping active session resources",
                    activeProxy.localPort()
                );
                activeProxy.closeListener();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeActiveProxyLocked() {
        if (activeProxy == null) {
            return;
        }
        try {
            activeProxy.close();
        } catch (Exception ignored) {
        }
        activeProxy = null;
    }

    private void closeActiveSessionLocked() {
        if (activeSession == null) {
            return;
        }
        if (activeSession != activeProxy) {
            try {
                activeSession.close();
            } catch (Exception ignored) {
            }
        }
        activeSession = null;
    }

    private void shutdown() {
        synchronized (stateLock) {
            closeActiveProxyLocked();
            closeActiveSessionLocked();
        }
    }

    private int showHudStatus(CommandContext<CommandSource> context) {
        boolean visible;
        synchronized (stateLock) {
            visible = hudVisible;
        }
        sendClientMessage(new TranslationTextComponent("zstdnet.command.hud.status", visible ? "ON" : "OFF"));
        return 1;
    }

    private int setHudVisible(CommandContext<CommandSource> context, boolean visible) {
        synchronized (stateLock) {
            hudVisible = visible;
        }
        sendClientMessage(new TranslationTextComponent(visible ? "zstdnet.command.hud.enabled" : "zstdnet.command.hud.disabled"));
        return 1;
    }

    private int toggleHudVisible(CommandContext<CommandSource> context) {
        boolean visible;
        synchronized (stateLock) {
            hudVisible = !hudVisible;
            visible = hudVisible;
        }
        sendClientMessage(new TranslationTextComponent(visible ? "zstdnet.command.hud.enabled" : "zstdnet.command.hud.disabled"));
        return 1;
    }

    private int showPortStatus(CommandContext<CommandSource> context) {
        sendClientMessage(new TranslationTextComponent(
            "zstdnet.command.port.status",
            ServerProxyConfigFile.readListenPort(),
            ServerProxyConfigFile.readTargetPort()
        ));
        return 1;
    }

    private int showVoicePortStatus(CommandContext<CommandSource> context) {
        sendClientMessage(new TranslationTextComponent(
            "zstdnet.command.port.voice_status",
            ServerProxyConfigFile.readVoiceListenPort(),
            ServerProxyConfigFile.readVoiceTargetPort()
        ));
        return 1;
    }

    private int setZstdPort(CommandContext<CommandSource> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(new TranslationTextComponent("zstdnet.command.port.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        int currentPort = ServerProxyConfigFile.readListenPort();
        if (port != currentPort && !isPortAvailable(port)) {
            sendClientMessage(ZSTD_PORT_UNAVAILABLE);
            return 0;
        }
        try {
            ServerProxyConfigFile.writeListenPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update zstd listen port {}", port, e);
            sendClientMessage(new TranslationTextComponent("zstdnet.command.port.write_failed"));
            return 0;
        }
        sendClientMessage(new TranslationTextComponent(
            isLanPublished() ? "zstdnet.command.port.zstd_reloaded" : "zstdnet.command.port.zstd_set",
            port
        ));
        return 1;
    }

    private int setGamePort(CommandContext<CommandSource> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(new TranslationTextComponent("zstdnet.command.port.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        try {
            ServerProxyConfigFile.writeTargetPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update game target port {}", port, e);
            sendClientMessage(new TranslationTextComponent("zstdnet.command.port.write_failed"));
            return 0;
        }
        sendClientMessage(new TranslationTextComponent(
            isLanPublished() ? "zstdnet.command.port.game_set_reopen" : "zstdnet.command.port.game_set",
            port
        ));
        return 1;
    }

    private int setVoicePort(CommandContext<CommandSource> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(new TranslationTextComponent("zstdnet.command.voice.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        try {
            ServerProxyConfigFile.writeVoiceTargetPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update voice target port {}", port, e);
            sendClientMessage(new TranslationTextComponent("zstdnet.command.voice.write_failed"));
            return 0;
        }
        sendClientMessage(new TranslationTextComponent(
            "zstdnet.command.port.voice_target_set",
            ServerProxyConfigFile.readVoiceTargetPort()
        ));
        return 1;
    }

    private int setVoiceListenPort(CommandContext<CommandSource> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(new TranslationTextComponent("zstdnet.command.voice.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        try {
            ServerProxyConfigFile.writeVoiceListenPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update voice listen port {}", port, e);
            sendClientMessage(new TranslationTextComponent("zstdnet.command.voice.write_failed"));
            return 0;
        }
        sendClientMessage(new TranslationTextComponent(
            "zstdnet.command.port.voice_listen_set",
            ServerProxyConfigFile.readVoiceListenPort()
        ));
        return 1;
    }

    private boolean canEditPortConfig() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
            && minecraft.getIntegratedServer() != null
            && minecraft.player.hasPermissionLevel(2);
    }

    private boolean isLanPublished() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getIntegratedServer() != null && minecraft.getIntegratedServer().getPublic();
    }

    private ServerData resolveDirectJoinServer(String remoteAddr) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerList serverList = new ServerList(minecraft);
        serverList.loadServerList();

        // 1.16.5 的 ServerList 没有按 IP 查找的 get(String)，手动遍历去重。
        for (int i = 0; i < serverList.countServers(); i++) {
            ServerData candidate = serverList.getServerData(i);
            if (candidate != null && remoteAddr.equals(candidate.serverIP)) {
                return candidate;
            }
        }

        ServerData created = new ServerData(I18n.format("selectServer.defaultName"), remoteAddr, false);
        serverList.addServerData(created);
        serverList.saveServerList();
        return created;
    }

    private RemoteTarget resolveRemoteTarget(String remoteAddr) {
        ServerAddress requested = ServerAddress.fromString(remoteAddr);
        if (requested == null || requested.getIP().trim().isEmpty()) {
            return null;
        }

        // 1.16.5 无 net.minecraft 的 ServerNameResolver/ResolvedServerAddress（1.18 才有）。代理需自行确定真实后端：
        // 先按 _minecraft._tcp.<host> 做 SRV 重定向（公网服常用，且与其余变体 ServerNameResolver 行为一致），
        // 命中则用其 target+port，未命中/出错回退字面 host:port（即原 A 记录行为，绝不回归）。
        String host = requested.getIP();
        int port = requested.getPort();
        SrvRedirect srv = lookupMinecraftSrv(host);
        if (srv != null) {
            host = srv.host;
            port = srv.port;
        }

        InetSocketAddress resolved;
        try {
            resolved = new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return null;
        }

        String connectHost = resolved.getHostString();
        if (connectHost == null || connectHost.trim().isEmpty()) {
            connectHost = host;
        }

        return new RemoteTarget(
            connectHost,
            port,
            connectHost,
            port,
            ConnectTargets.isDirectLanTarget(resolved)
        );
    }

    /**
     * 解析 {@code _minecraft._tcp.<host>} 的 SRV 记录（JNDI DNS，Java 8 自带 {@code com.sun.jndi.dns}）。
     * 这是 MC 自身及本仓库 1.18+ 变体所用 {@code ServerNameResolver} 内部做的同一件事，在 1.16.5 没有现成类，故自实现。
     * host 为字面 IP、无 SRV 记录、或任何异常时返回 {@code null}，调用方回退到字面 {@code host:port}。
     */
    private static SrvRedirect lookupMinecraftSrv(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty() || isLiteralIp(trimmed)) {
            return null;
        }

        DirContext ctx = null;
        try {
            Hashtable<String, String> env = new Hashtable<String, String>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.initial", "5000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            ctx = new InitialDirContext(env);
            Attributes attributes = ctx.getAttributes("_minecraft._tcp." + trimmed, new String[] {"SRV"});
            if (attributes == null) {
                return null;
            }
            Attribute srv = attributes.get("srv");
            if (srv == null || srv.size() == 0) {
                return null;
            }
            // SRV rdata 形如 "priority weight port target."
            String[] parts = srv.get(0).toString().trim().split("\\s+");
            if (parts.length < 4) {
                return null;
            }
            int srvPort = Integer.parseInt(parts[2]);
            String target = parts[3];
            if (target.endsWith(".")) {
                target = target.substring(0, target.length() - 1);
            }
            if (target.trim().isEmpty() || srvPort < 1 || srvPort > 65535) {
                return null;
            }
            LOGGER.info("[zstdnet-client] SRV redirect {} -> {}:{}", trimmed, target, srvPort);
            return new SrvRedirect(target, srvPort);
        } catch (Throwable ignored) {
            // SRV 是可选优化：解析失败/无 DNS 时回退字面地址，不影响连接。
            return null;
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                    // ignore close failure
                }
            }
        }
    }

    /** 判断 host 是否为字面 IP（IPv6 含 ':'，IPv4 点分四段），字面 IP 不做 SRV。 */
    private static boolean isLiteralIp(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    /** SRV 重定向结果（MC 无关纯数据）。 */
    private static final class SrvRedirect {
        final String host;
        final int port;

        SrvRedirect(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static String normalizeAddress(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static boolean isValidServerAddress(String raw) {
        // 1.16.5 没有 ServerAddress.isValidAddress；非空且能解析出地址即视为有效，余下交连接阶段兜底。
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        try {
            return ServerAddress.fromString(raw) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSelectButton(Button button) {
        return button != null && Objects.equals(button.getMessage().getString(), I18n.format("selectServer.select"));
    }

    private static boolean isLanStartButton(Button button) {
        return button != null && Objects.equals(button.getMessage().getString(), I18n.format("lanServer.start"));
    }

    private static boolean isLanPortEdit(TextFieldWidget editBox) {
        return editBox != null && Objects.equals(editBox.getMessage().getString(), I18n.format("lanServer.port"));
    }

    private static boolean isShareToLanLikeScreen(Screen screen) {
        if (screen instanceof ShareToLanScreen) {
            return true;
        }
        return screen != null && screen.getClass().getName().toLowerCase(Locale.ROOT).contains("sharetolan");
    }

    private static boolean looksLikeVanillaLanPortEdit(TextFieldWidget editBox, Screen screen) {
        if (editBox == null) {
            return false;
        }
        return editBox.getWidth() == 150
            && editBox.getHeight() == 20
            && editBox.y == 160
            && Math.abs(editBox.x - (screen.width / 2 - 75)) <= 4;
    }

    private static TextFieldWidget findBackendPortEdit(List<?> listeners, Screen screen) {
        TextFieldWidget exact = null;
        TextFieldWidget fallback = null;

        for (Object listener : listeners) {
            if (!(listener instanceof TextFieldWidget)) {
                continue;
            }
            TextFieldWidget editBox = (TextFieldWidget) listener;
            if (isLanPortEdit(editBox)) {
                return editBox;
            }
            if (looksLikeVanillaLanPortEdit(editBox, screen)) {
                exact = editBox;
            }
            if (fallback == null || editBox.y < fallback.y || (editBox.y == fallback.y && editBox.x < fallback.x)) {
                fallback = editBox;
            }
        }

        return exact != null ? exact : fallback;
    }

    private static int findLowestEditBoxBottom(List<?> listeners) {
        int bottom = Integer.MIN_VALUE;
        for (Object listener : listeners) {
            if (listener instanceof TextFieldWidget) {
                TextFieldWidget editBox = (TextFieldWidget) listener;
                bottom = Math.max(bottom, editBox.y + editBox.getHeight());
            }
        }
        return bottom;
    }

    private static Integer tryReadPort(TextFieldWidget editBox) {
        if (editBox == null) {
            return null;
        }
        String raw = editBox.getText();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= MIN_PORT && port <= MAX_PORT ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unnamed" : value.trim();
    }

    private static void sendClientMessage(ITextComponent message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendMessage(message, Util.DUMMY_UUID);
        } else {
            LOGGER.info(message.getString());
        }
    }

    private void updateRemoteServerHudSnapshot(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        synchronized (stateLock) {
            remoteServerHudSnapshot = snapshot;
            remoteServerHudSnapshotMillis = System.currentTimeMillis();
        }
    }

    private ServerProxyBootstrap.ServerHudSnapshot visibleHostSnapshot() {
        ServerProxyBootstrap.ServerHudSnapshot localSnapshot = ServerProxyBootstrap.currentHudSnapshot();
        if (localSnapshot != null) {
            return localSnapshot;
        }

        synchronized (stateLock) {
            if (remoteServerHudSnapshot == null) {
                return null;
            }
            if (System.currentTimeMillis() - remoteServerHudSnapshotMillis > REMOTE_HUD_SNAPSHOT_TTL_MS) {
                remoteServerHudSnapshot = null;
                remoteServerHudSnapshotMillis = 0L;
                return null;
            }
            return remoteServerHudSnapshot;
        }
    }

    private static String formatRate(long bytesPerSecond) {
        return formatSize(bytesPerSecond) + "/s";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }

        String[] units = SIZE_UNITS;
        double value = bytes / 1024.0D;
        int unit = 0;
        while (value >= 1024.0D && unit < units.length - 1) {
            value /= 1024.0D;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static int renderHudPanel(
        MatrixStack gui,
        Minecraft minecraft,
        int startY,
        String[] lines,
        int backgroundColor,
        int titleColor,
        int textColor
    ) {
        int x = 8;
        int y = startY;
        int lineHeight = 10;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, minecraft.fontRenderer.getStringWidth(line));
        }
        width += 8;
        int height = lineHeight * lines.length + 6;

        AbstractGui.fill(gui, x - 3, y - 3, x - 3 + width, y - 3 + height, backgroundColor);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 ? titleColor : textColor;
            minecraft.fontRenderer.drawStringWithShadow(gui, lines[i], x, y + lineHeight * i, color);
        }
        return y + height + 4;
    }

    private static String translateServerHudMode(String mode) {
        if (mode == null) {
            return "";
        }
        if ("DEDICATED".equals(mode)) {
            return I18n.format("zstdnet.hud.mode.dedicated");
        }
        if ("LAN".equals(mode)) {
            return I18n.format("zstdnet.hud.mode.lan");
        }
        return mode;
    }

    private static String translateClientHudMode(LocalZstdNet.Mode mode) {
        if (mode == null) {
            return "";
        }
        switch (mode) {
            case AUTO:
                return I18n.format("zstdnet.hud.mode.auto");
            case RAW:
                return I18n.format("zstdnet.hud.mode.raw");
            case ZSTD:
                return I18n.format("zstdnet.hud.mode.zstd");
            default:
                return "";
        }
    }

    private static String formatPercent(double value) {
        return String.format("%.2f%%", value);
    }

    private ShareToLanState attachShareToLanState(Screen screen, GuiScreenEvent.InitGuiEvent.Post event, List<?> listeners) {
        ShareToLanState existing = getShareToLanState(screen);
        if (existing != null) {
            event.removeWidget(existing.zstdPortEdit);
        }

        // 局域网默认直连：不注入 Zstd 端口框，「开放到局域网」保持纯原版界面。
        if (!ClientConfig.compressLan()) {
            return null;
        }

        TextFieldWidget backendPortEdit = findBackendPortEdit(listeners, screen);
        Button vanillaStartButton = null;
        for (Object listener : listeners) {
            if (listener instanceof Button) {
                Button button = (Button) listener;
                if (isLanStartButton(button)) {
                    vanillaStartButton = button;
                }
            }
        }

        if (vanillaStartButton == null) {
            return null;
        }

        int preferredZstdPort = ServerProxyConfigFile.readListenPort();
        int defaultBackendPort = resolveBackendPortForLan(backendPortEdit, preferredZstdPort);
        int defaultZstdPort = findAvailableZstdPort(preferredZstdPort, defaultBackendPort);
        int zstdFieldWidth = 150;
        int zstdFieldHeight = 20;
        int zstdFieldX = screen.width / 2 - zstdFieldWidth / 2;
        int zstdFieldY = vanillaStartButton.y - 28;
        if (backendPortEdit != null) {
            zstdFieldWidth = backendPortEdit.getWidth();
            zstdFieldHeight = backendPortEdit.getHeight();
            zstdFieldX = Math.max(8, backendPortEdit.x - zstdFieldWidth - 8);
            zstdFieldY = backendPortEdit.y;
        } else {
            int lowestEditBottom = findLowestEditBoxBottom(listeners);
            if (lowestEditBottom != Integer.MIN_VALUE) {
                zstdFieldY = Math.min(lowestEditBottom + 28, vanillaStartButton.y - 28);
            }
        }
        TextFieldWidget zstdPortEdit = new TextFieldWidget(
            Minecraft.getInstance().fontRenderer,
            zstdFieldX,
            zstdFieldY,
            zstdFieldWidth,
            zstdFieldHeight,
            ZSTD_PORT_LABEL
        );
        // 1.16.5 的 TextFieldWidget 没有 setHint/Tooltip API，端口校验错误只能用文字颜色提示。
        zstdPortEdit.setMaxStringLength(5);
        zstdPortEdit.setText(String.valueOf(defaultZstdPort));

        ShareToLanState state = new ShareToLanState(backendPortEdit, vanillaStartButton, zstdPortEdit, defaultZstdPort);
        zstdPortEdit.setResponder(raw -> applyZstdPortResponse(state, raw));
        applyZstdPortResponse(state, zstdPortEdit.getText());

        event.addWidget(zstdPortEdit);
        return state;
    }

    private void applyZstdPortResponse(ShareToLanState state, String raw) {
        PortValidation validation = validateZstdPort(raw, state.defaultZstdPort, state.backendPortEdit);
        state.zstdPort = validation.port();
        state.zstdError = validation.error();
        state.zstdPortEdit.setTextColor(validation.error() == null ? PORT_TEXT_NORMAL : PORT_TEXT_INVALID);
    }

    private static int findAvailableZstdPort(int preferredPort, Integer reservedPort) {
        int startPort = clampPort(preferredPort);
        for (int port = startPort; port <= MAX_PORT; port++) {
            if (isZstdPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < startPort; port++) {
            if (isZstdPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            return isReservedZstdPort(port, reservedPort) ? startPort : port;
        } catch (IOException ignored) {
            return startPort;
        }
    }

    private static boolean isZstdPortAvailable(int port, Integer reservedPort) {
        return !isReservedZstdPort(port, reservedPort) && isPortAvailable(port);
    }

    private static boolean isReservedZstdPort(int port, Integer reservedPort) {
        return Objects.equals(port, reservedPort);
    }

    private static int resolveBackendPortForLan(TextFieldWidget backendPortEdit, int zstdPort) {
        Integer backendPort = tryReadPort(backendPortEdit);
        if (backendPort != null) {
            return backendPort;
        }
        return findAvailableBackendPort(ServerProxyConfigFile.readTargetPort(), zstdPort);
    }

    private static int findAvailableBackendPort(int preferredPort, int reservedPort) {
        int startPort = clampPort(preferredPort);
        if (isBackendPortAvailable(startPort, reservedPort)) {
            return startPort;
        }
        int defaultPort = clampPort(DEFAULT_BACKEND_PORT);
        if (isBackendPortAvailable(defaultPort, reservedPort)) {
            return defaultPort;
        }
        for (int port = Math.max(MIN_PORT, startPort + 1); port <= MAX_PORT; port++) {
            if (isBackendPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < startPort; port++) {
            if (isBackendPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            return port == reservedPort ? startPort : port;
        } catch (IOException ignored) {
            return startPort;
        }
    }

    private static boolean isBackendPortAvailable(int port, int reservedPort) {
        return port != reservedPort && isPortAvailable(port);
    }

    /** 1.16.5 的 HttpUtil 还没有 isPortAvailable，用本地绑定探测代替。 */
    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int clampPort(int port) {
        return Math.max(MIN_PORT, Math.min(MAX_PORT, port));
    }

    private static void ensureShareToLanWidgetAttached(Screen screen, ShareToLanState state) {
        if (!reattachWidgetToScreenLists(screen, state.zstdPortEdit)) {
            LOGGER.debug("zstdnet: failed to reattach share-to-lan zstd port field");
        }
    }

    private static boolean reattachWidgetToScreenLists(Screen screen, TextFieldWidget widget) {
        boolean attached = false;
        for (Class<?> type = Screen.class; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof List<?>) {
                        List<?> list = (List<?>) value;
                        attached |= addWidgetToRawList(list, widget);
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    LOGGER.debug("zstdnet: failed to inspect screen widget list for share-to-lan field", e);
                }
            }
        }
        return screen.getEventListeners().contains(widget);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean addWidgetToRawList(List list, TextFieldWidget widget) {
        if (list.contains(widget)) {
            return false;
        }
        list.add(widget);
        return true;
    }

    private PortValidation validateZstdPort(String raw, int fallbackPort, TextFieldWidget backendPortEdit) {
        String text = raw == null ? "" : raw.trim();
        int port = fallbackPort;

        if (!text.isEmpty()) {
            try {
                port = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return new PortValidation(fallbackPort, ZSTD_PORT_INVALID);
            }
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            return new PortValidation(fallbackPort, ZSTD_PORT_INVALID);
        }
        if (!isZstdPortAvailable(port, resolveBackendPortForLan(backendPortEdit, port))) {
            return new PortValidation(port, ZSTD_PORT_UNAVAILABLE);
        }
        return new PortValidation(port, null);
    }

    private boolean prepareLanWorldPublish(ShareToLanState state) {
        applyZstdPortResponse(state, state.zstdPortEdit.getText());
        if (state.zstdError != null) {
            return false;
        }
        int backendPort = resolveBackendPortForLan(state.backendPortEdit, state.zstdPort);
        if (state.backendPortEdit != null && (state.backendPortEdit.getText() == null || state.backendPortEdit.getText().trim().isEmpty())) {
            state.backendPortEdit.setText(String.valueOf(backendPort));
        }
        try {
            ServerProxyConfigFile.writePorts(state.zstdPort, backendPort);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to write LAN zstd port {}", state.zstdPort, e);
            sendClientMessage(new TranslationTextComponent("zstdnet.share_to_lan.write_failed"));
            return false;
        }
        return true;
    }

    private static final class JoinScreenState {
        private final ServerSelectionList serverList;
        private final Button selectButton;

        private JoinScreenState(ServerSelectionList serverList, Button selectButton) {
            this.serverList = serverList;
            this.selectButton = selectButton;
        }

        private static JoinScreenState from(List<?> listeners) {
            ServerSelectionList list = null;
            Button select = null;

            for (Object listener : listeners) {
                if (listener instanceof ServerSelectionList) {
                    list = (ServerSelectionList) listener;
                } else if (listener instanceof Button) {
                    Button button = (Button) listener;
                    if (isSelectButton(button)) {
                        select = button;
                    }
                }
            }

            return new JoinScreenState(list, select);
        }
    }

    private static final class DirectJoinState {
        private final TextFieldWidget ipEdit;
        private final Button selectButton;

        private DirectJoinState(TextFieldWidget ipEdit, Button selectButton) {
            this.ipEdit = ipEdit;
            this.selectButton = selectButton;
        }

        private static DirectJoinState from(List<?> listeners) {
            TextFieldWidget ipEdit = null;
            Button select = null;

            for (Object listener : listeners) {
                if (listener instanceof TextFieldWidget) {
                    ipEdit = (TextFieldWidget) listener;
                } else if (listener instanceof Button) {
                    Button button = (Button) listener;
                    if (isSelectButton(button)) {
                        select = button;
                    }
                }
            }

            return new DirectJoinState(ipEdit, select);
        }
    }

    private static final class ShareToLanState {
        private final TextFieldWidget backendPortEdit;
        private final Button vanillaStartButton;
        private final TextFieldWidget zstdPortEdit;
        private final int defaultZstdPort;
        private ITextComponent zstdError;
        private int zstdPort;

        private ShareToLanState(
            TextFieldWidget backendPortEdit,
            Button vanillaStartButton,
            TextFieldWidget zstdPortEdit,
            int defaultZstdPort
        ) {
            this.backendPortEdit = backendPortEdit;
            this.vanillaStartButton = vanillaStartButton;
            this.zstdPortEdit = zstdPortEdit;
            this.defaultZstdPort = defaultZstdPort;
            this.zstdPort = defaultZstdPort;
        }
    }

    private static final class PortValidation {
        private final int port;
        private final ITextComponent error;

        private PortValidation(int port, ITextComponent error) {
            this.port = port;
            this.error = error;
        }

        private int port() {
            return port;
        }

        private ITextComponent error() {
            return error;
        }
    }

    private static final class RemoteTarget {
        private final String connectHost;
        private final int connectPort;
        private final String presentedHost;
        private final int presentedPort;
        private final boolean directLan;

        private RemoteTarget(String connectHost, int connectPort, String presentedHost, int presentedPort, boolean directLan) {
            this.connectHost = connectHost;
            this.connectPort = connectPort;
            this.presentedHost = presentedHost;
            this.presentedPort = presentedPort;
            this.directLan = directLan;
        }

        private String connectHost() {
            return connectHost;
        }

        private int connectPort() {
            return connectPort;
        }

        private String presentedHost() {
            return presentedHost;
        }

        private int presentedPort() {
            return presentedPort;
        }

        private boolean directLan() {
            return directLan;
        }
    }
}
