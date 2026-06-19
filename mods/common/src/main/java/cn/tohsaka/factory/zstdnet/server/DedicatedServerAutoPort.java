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

package cn.tohsaka.factory.zstdnet.server;

import cn.tohsaka.factory.zstdnet.auth.PremiumAuthState;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.dedicated.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class DedicatedServerAutoPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(DedicatedServerAutoPort.class);
    private static final int DEFAULT_PUBLIC_PORT = 25565;
    private static final int ZSTD_COMPRESSION_THRESHOLD = 1048576;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int AUTO_SCAN_START_PORT = 1024;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final String DEFAULT_TARGET_HOST = "127.0.0.1";

    private DedicatedServerAutoPort() {
    }

    public static DedicatedServerProperties prepareDedicatedServerProperties(
        DedicatedServer server,
        DedicatedServerProperties current
    ) {
        if (server == null || current == null || server.isSingleplayer()) {
            DedicatedAutoPortState.clear();
            PremiumAuthState.disable();
            return current;
        }

        AutoPortConfig config = loadConfig();
        if (!config.enabled) {
            DedicatedAutoPortState.clear();
            PremiumAuthState.disable();
            return current;
        }

        // A0 自动检测：跟随 server.properties 的 online-mode（管理员意图）解析三态总开关。
        // 仅当本加载器变体实现了登录挂钩时才启用，否则保持历史行为（绝不在无法验证时擅自把后端切离线）。
        boolean onlineMode = readOnlineMode();
        boolean platformSupports = Platforms.get().supportsPremiumVerification();
        boolean verificationEnabled = platformSupports
            && PremiumAuthState.resolveEnabled(ServerProxyConfigFile.readPremiumVerification(), onlineMode);
        boolean forceOfflineMode = verificationEnabled;
        // 验证启用 → 后端切离线（保压缩）；或后端本就离线 → 照常接管压缩。
        boolean shouldForceCompressionThreshold = verificationEnabled || !onlineMode;
        applyPremiumAuthState(verificationEnabled, onlineMode, platformSupports);

        if (!config.autoTakeover) {
            DedicatedAutoPortState.clear();
            DedicatedServerProperties rewritten = rewriteProperties(current, null, shouldForceCompressionThreshold, forceOfflineMode);
            if (rewritten == null) {
                LOGGER.error("[zstdnet-server] failed to force dedicated compression threshold. Keeping current server.properties values.");
                return current;
            }
            if (!replaceSettingsProperties(server, rewritten)) {
                LOGGER.warn("[zstdnet-server] dedicated server properties were rewritten in returned properties, but DedicatedServerSettings cache could not be updated.");
            }
            persistCompressionThreshold(normalizePort(server.getPort() > 0 ? server.getPort() : current.serverPort, DEFAULT_PUBLIC_PORT), shouldForceCompressionThreshold);
            logCompressionThresholdDecision(shouldForceCompressionThreshold);
            return rewritten;
        }

        int publicPort = normalizePort(server.getPort() > 0 ? server.getPort() : current.serverPort, DEFAULT_PUBLIC_PORT);
        int backendPort;
        try {
            backendPort = chooseBackendPort(publicPort, current.serverIp, config.configuredTargetPort);
        } catch (IllegalStateException e) {
            LOGGER.error("[zstdnet-server] auto takeover could not find a free backend port after reserving public port {}.", publicPort);
            DedicatedAutoPortState.clear();
            return current;
        }
        DedicatedServerProperties rewritten = rewriteProperties(current, backendPort, shouldForceCompressionThreshold, forceOfflineMode);
        if (rewritten == null) {
            LOGGER.error("[zstdnet-server] auto takeover could not rewrite dedicated server properties. Falling back to manual mode.");
            DedicatedAutoPortState.clear();
            return current;
        }

        if (!replaceSettingsProperties(server, rewritten)) {
            LOGGER.warn("[zstdnet-server] auto takeover could not replace DedicatedServerSettings cache; continuing with in-memory port override only.");
        }

        server.setPort(backendPort);
        DedicatedAutoPortState.set(new AutoPortPlan(
            config.listenHost,
            publicPort,
            DEFAULT_TARGET_HOST,
            backendPort
        ));

        try {
            persistResolvedConfig(config.listenHost, publicPort, backendPort);
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed to persist resolved auto takeover config: {}", e.toString());
        }
        persistCompressionThreshold(publicPort, shouldForceCompressionThreshold);

        LOGGER.info(
            "[zstdnet-server] auto takeover armed: public_entry={}:{} backend={}:{}",
            config.listenHost,
            publicPort,
            DEFAULT_TARGET_HOST,
            backendPort
        );
        LOGGER.info("[zstdnet-server] server.properties port is now the public entry; backend port was reassigned automatically.");
        logCompressionThresholdDecision(shouldForceCompressionThreshold);
        return rewritten;
    }

    /** 读取 server.properties 的 online-mode（管理员意图）；缺失/读失败按原版默认 true 处理。 */
    private static boolean readOnlineMode() {
        Path path = Path.of("server.properties");
        if (!Files.exists(path)) {
            return true;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed reading {} while checking online-mode: {}", path, e.toString());
            return true;
        }
        return parseBoolean(props.getProperty("online-mode"), true);
    }

    /** 据解析结果写入 {@link PremiumAuthState}，并在各遗留/未支持态打 WARN。 */
    private static void applyPremiumAuthState(boolean verificationEnabled, boolean onlineMode, boolean platformSupports) {
        if (verificationEnabled) {
            boolean strict = PremiumAuthState.resolveStrict(ServerProxyConfigFile.readPremiumVerificationMode());
            PremiumAuthState.configure(
                true,
                strict,
                ServerProxyConfigFile.readPremiumSessionServer(),
                ServerProxyConfigFile.readPremiumPassRealIp()
            );
            LOGGER.info("[zstdnet-server] built-in premium verification enabled (mode={}); backend forced to offline-mode in memory so zstd compression stays effective.",
                strict ? "strict" : "lenient");
            return;
        }
        PremiumAuthState.disable();
        if (!onlineMode) {
            return;
        }
        // online-mode=true 但未启用验证：要么本加载器尚未实现挂钩，要么管理员显式关掉了。
        boolean wantsVerification = PremiumAuthState.resolveEnabled(ServerProxyConfigFile.readPremiumVerification(), true);
        if (!platformSupports && wantsVerification) {
            LOGGER.warn("[zstdnet-server] built-in premium verification is not implemented on this loader yet; keeping vanilla online-mode/encryption (so zstd compression yields almost nothing). Use a Fabric build for built-in verification, or set online-mode=false (optionally pairing TrueUUID). See PREMIUM_VERIFICATION.md.");
        } else {
            LOGGER.warn("[zstdnet-server] online-mode=true but premium_verification=off -> vanilla encryption stays on and zstd compression yields almost nothing. Set premium_verification=auto/on to keep both premium identity and compression.");
        }
    }

    static AutoPortPlan activePlan() {
        return DedicatedAutoPortState.activePlan();
    }

    static void clear() {
        DedicatedAutoPortState.clear();
    }

    private static AutoPortConfig loadConfig() {
        Path path = ServerProxyConfigFile.path();
        Properties props = new Properties();
        boolean exists = Files.exists(path);

        if (exists) {
            try (var in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.warn("[zstdnet-server] failed reading {} for auto takeover: {}", path, e.toString());
            }
        }

        boolean enabled = parseBoolean(props.getProperty("enabled"), true);
        boolean autoTakeover = parseBoolean(props.getProperty("auto_takeover"), !exists);
        String listenHost = parseHost(props.getProperty("listen"), DEFAULT_LISTEN_HOST);
        int configuredTargetPort = ServerProxyConfigFile.parsePort(props.getProperty("target"), -1);
        return new AutoPortConfig(enabled, autoTakeover, listenHost, configuredTargetPort);
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static String parseHost(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String host = ServerProxyConfigFile.parseHost(raw, fallback);
        return host == null || host.isBlank() ? fallback : host;
    }

    private static int normalizePort(int port, int fallback) {
        return port >= MIN_PORT && port <= MAX_PORT ? port : fallback;
    }

    private static int chooseBackendPort(int publicPort, String bindHost, int configuredTargetPort) {
        String hostToProbe = bindHost == null || bindHost.isBlank() ? DEFAULT_LISTEN_HOST : bindHost.trim();
        if (configuredTargetPort >= MIN_PORT
            && configuredTargetPort <= MAX_PORT
            && configuredTargetPort != publicPort
            && isBindable(hostToProbe, configuredTargetPort)) {
            return configuredTargetPort;
        }

        int start = Math.max(AUTO_SCAN_START_PORT, Math.min(MAX_PORT, publicPort + 1));
        for (int port = start; port <= MAX_PORT; port++) {
            if (port != publicPort && isBindable(hostToProbe, port)) {
                return port;
            }
        }
        for (int port = AUTO_SCAN_START_PORT; port < start; port++) {
            if (port != publicPort && isBindable(hostToProbe, port)) {
                return port;
            }
        }

        throw new IllegalStateException("no free backend port available for zstd auto takeover");
    }

    private static boolean isBindable(String host, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            InetSocketAddress address;
            if (host == null || host.isBlank() || "0.0.0.0".equals(host)) {
                address = new InetSocketAddress(port);
            } else {
                address = new InetSocketAddress(InetAddress.getByName(host), port);
            }
            socket.bind(address);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static DedicatedServerProperties rewriteProperties(
        DedicatedServerProperties current,
        Integer backendPort,
        boolean forceCompressionThreshold,
        boolean forceOfflineMode
    ) {
        try {
            Properties base = extractProperties(current);
            Properties copy = new Properties();
            copy.putAll(base);
            if (backendPort != null) {
                copy.setProperty("server-port", String.valueOf(backendPort));
            }
            if (forceCompressionThreshold) {
                copy.setProperty("network-compression-threshold", String.valueOf(ZSTD_COMPRESSION_THRESHOLD));
            }
            if (forceOfflineMode) {
                // 仅内存生效：本钩子在 DedicatedServer.initServer() 读取 props 之后、调用 setUsesAuthentication(props.onlineMode)
                // 之前替换该局部变量，故 online-mode=false 会被采用、原版加密不触发，zstd 压缩照常。
                // 切勿回写磁盘 server.properties —— 磁盘上的 online-mode 要保留作为下次启动 auto 判定的「管理员意图」信号。
                copy.setProperty("online-mode", "false");
            }
            return new DedicatedServerProperties(copy);
        } catch (Exception e) {
            LOGGER.warn("[zstdnet-server] failed to clone DedicatedServerProperties: {}", e.toString());
            return null;
        }
    }

    private static Properties extractProperties(Settings<?> settings) throws IllegalAccessException {
        for (Class<?> type = settings.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Properties.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(settings);
                if (value instanceof Properties props) {
                    return props;
                }
            }
        }
        throw new IllegalStateException("Settings.properties field not found");
    }

    private static boolean replaceSettingsProperties(DedicatedServer server, DedicatedServerProperties rewritten) {
        try {
            DedicatedServerSettings settings = findFieldValue(server, DedicatedServerSettings.class);
            if (settings == null) {
                return false;
            }

            for (Field field : settings.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !DedicatedServerProperties.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                field.set(settings, rewritten);
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("[zstdnet-server] failed updating DedicatedServerSettings: {}", e.toString());
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T findFieldValue(Object target, Class<T> expectedType) throws IllegalAccessException {
        if (target == null || expectedType == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !expectedType.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null) {
                    return (T) value;
                }
            }
        }
        return null;
    }

    private static void persistResolvedConfig(
        String listenHost,
        int listenPort,
        int targetPort
    ) throws IOException {
        ServerProxyConfigFile.writeResolvedAutoTakeoverConfig(listenHost, listenPort, targetPort);
    }

    private static void persistCompressionThreshold(int publicPort, boolean forceCompressionThreshold) {
        Path path = Path.of("server.properties");
        if (!Files.exists(path)) {
            return;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
            boolean hasServerPort = false;
            boolean hasCompression = false;

            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                if (trimmed.startsWith("server-port=")) {
                    lines.set(i, "server-port=" + publicPort);
                    hasServerPort = true;
                    continue;
                }
                if (forceCompressionThreshold && trimmed.startsWith("network-compression-threshold=")) {
                    lines.set(i, "network-compression-threshold=" + ZSTD_COMPRESSION_THRESHOLD);
                    hasCompression = true;
                }
            }

            if (!hasServerPort) {
                lines.add("server-port=" + publicPort);
            }
            if (forceCompressionThreshold && !hasCompression) {
                lines.add("network-compression-threshold=" + ZSTD_COMPRESSION_THRESHOLD);
            }

            Files.writeString(path, String.join(lineSeparator, lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed persisting server.properties compression threshold: {}", e.toString());
        }
    }

    private static void logCompressionThresholdDecision(boolean forceCompressionThreshold) {
        if (forceCompressionThreshold) {
            LOGGER.info("[zstdnet-server] forced dedicated network-compression-threshold={} for built-in zstd proxy.", ZSTD_COMPRESSION_THRESHOLD);
            return;
        }
        LOGGER.info("[zstdnet-server] keeping dedicated network-compression-threshold unchanged because online-mode=true and premium_verification=off.");
    }

    private record AutoPortConfig(
        boolean enabled,
        boolean autoTakeover,
        String listenHost,
        int configuredTargetPort
    ) {
    }
}
