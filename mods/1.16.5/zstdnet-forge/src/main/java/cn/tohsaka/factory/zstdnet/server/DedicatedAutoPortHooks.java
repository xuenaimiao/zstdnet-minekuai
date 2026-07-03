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
import net.minecraft.server.dedicated.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * 专用服「自动接管」端口准备（1.16.5 版）。
 * <p>
 * 这是 {@code mods/common} 里 {@link DedicatedServerAutoPort} 的 1.16.5 等价物——后者依赖 1.17+ 的
 * {@code DedicatedServerProperties}（可重建、构造器单参）与 {@code DedicatedServerSettings} 等签名，在 1.16.5
 * 不存在，故 common 的那份文件在本变体 build 里被 {@code exclude}，由本类替代。
 * <p>
 * 关键差异：1.16.5 的 {@link ServerProperties} 构造器是双参 {@code (Properties, DynamicRegistries)}，重建很麻烦；
 * 但它把 {@code server-port / online-mode / network-compression-threshold} 暴露为 <b>public final</b> 实例字段，
 * 故本类<b>原地反射改这些 final 字段</b>（JDK8 上对非静态 final 实例字段 {@code setAccessible(true)+set} 合法），
 * 并同步改底层 {@code Properties} 映射条目，然后把同一个 {@link ServerProperties} 实例原样返回——
 * {@code DedicatedServer.init()} 紧随其后读这些字段（{@code setServerPort(props.serverPort)} 等），即采用我方值。
 * <p>
 * 由 {@code coremods/zstdnet_lan_compression_threshold.js}（含 dedicated_auto_port 段）在
 * {@code DedicatedServer.init()} 内 {@code getServerProperties()} 返回并 ASTORE 之前注入调用。
 */
public final class DedicatedAutoPortHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(DedicatedAutoPortHooks.class);
    private static final int DEFAULT_PUBLIC_PORT = 25565;
    private static final int ZSTD_COMPRESSION_THRESHOLD = 1048576;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int AUTO_SCAN_START_PORT = 1024;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final String DEFAULT_TARGET_HOST = "127.0.0.1";

    private DedicatedAutoPortHooks() {
    }

    /**
     * coremod 注入点。返回（原地改过的）同一个 {@link ServerProperties} 实例。
     */
    public static ServerProperties prepareDedicatedServerProperties(
        DedicatedServer server,
        ServerProperties current
    ) {
        if (server == null || current == null || server.isSinglePlayer()) {
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

        int publicPort = normalizePort(server.getServerPort() > 0 ? server.getServerPort() : current.serverPort, DEFAULT_PUBLIC_PORT);

        if (!config.autoTakeover) {
            DedicatedAutoPortState.clear();
            applyPropertyOverrides(current, null, shouldForceCompressionThreshold, forceOfflineMode);
            persistCompressionThreshold(publicPort, shouldForceCompressionThreshold);
            logCompressionThresholdDecision(shouldForceCompressionThreshold);
            return current;
        }

        int backendPort;
        try {
            backendPort = chooseBackendPort(publicPort, current.serverIp, config.configuredTargetPort);
        } catch (IllegalStateException e) {
            LOGGER.error("[zstdnet-server] auto takeover could not find a free backend port after reserving public port {}.", publicPort);
            DedicatedAutoPortState.clear();
            return current;
        }

        if (!applyPropertyOverrides(current, backendPort, shouldForceCompressionThreshold, forceOfflineMode)) {
            LOGGER.error("[zstdnet-server] auto takeover could not rewrite dedicated server properties. Falling back to manual mode.");
            DedicatedAutoPortState.clear();
            return current;
        }

        server.setServerPort(backendPort);
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
        return current;
    }

    /** 读取 server.properties 的 online-mode（管理员意图）；缺失/读失败按原版默认 true 处理。 */
    private static boolean readOnlineMode() {
        Path path = Paths.get("server.properties");
        if (!Files.exists(path)) {
            return true;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
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
                ServerProxyConfigFile.readPremiumPassRealIp(),
                ServerProxyConfigFile.readPremiumUuidGuard()
            );
            LOGGER.info("[zstdnet-server] built-in premium verification enabled (mode={}, uuid_guard={}, session_servers={}); backend forced to offline-mode in memory so zstd compression stays effective.",
                strict ? "strict" : "lenient", PremiumAuthState.uuidGuardEnabled(), PremiumAuthState.sessionBaseUrls());
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

    private static AutoPortConfig loadConfig() {
        Path path = ServerProxyConfigFile.path();
        Properties props = new Properties();
        boolean exists = Files.exists(path);

        if (exists) {
            try (InputStream in = Files.newInputStream(path)) {
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
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static String parseHost(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String host = ServerProxyConfigFile.parseHost(raw, fallback);
        return host == null || host.trim().isEmpty() ? fallback : host;
    }

    private static int normalizePort(int port, int fallback) {
        return port >= MIN_PORT && port <= MAX_PORT ? port : fallback;
    }

    private static int chooseBackendPort(int publicPort, String bindHost, int configuredTargetPort) {
        String hostToProbe = bindHost == null || bindHost.trim().isEmpty() ? DEFAULT_LISTEN_HOST : bindHost.trim();
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
            if (host == null || host.trim().isEmpty() || "0.0.0.0".equals(host)) {
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

    /**
     * 原地改写 1.16.5 {@link ServerProperties}：反射写 public final 字段
     * {@code serverPort / onlineMode / networkCompressionThreshold}，并同步底层 {@code Properties} 映射条目。
     * 返回是否成功（任一关键字段写失败即 false，调用方回退手动模式）。
     */
    private static boolean applyPropertyOverrides(
        ServerProperties current,
        Integer backendPort,
        boolean forceCompressionThreshold,
        boolean forceOfflineMode
    ) {
        boolean ok = true;
        Properties map = extractProperties(current);

        if (backendPort != null) {
            ok &= setIntField(current, "serverPort", backendPort);
            if (map != null) {
                map.setProperty("server-port", String.valueOf(backendPort));
            }
        }
        if (forceCompressionThreshold) {
            // 切勿依赖该字段是否被 1.16.5 DedicatedServer 直接读取——两手都改最稳。
            setIntField(current, "networkCompressionThreshold", ZSTD_COMPRESSION_THRESHOLD);
            if (map != null) {
                map.setProperty("network-compression-threshold", String.valueOf(ZSTD_COMPRESSION_THRESHOLD));
            }
        }
        if (forceOfflineMode) {
            // 仅内存生效：init() 在读取本 props 后、调用 setOnlineMode(props.onlineMode) 之前看到 false，
            // 故原版加密不触发、zstd 压缩照常。切勿回写磁盘 server.properties 的 online-mode（保留作下次启动「管理员意图」）。
            setBooleanField(current, "onlineMode", false);
            if (map != null) {
                map.setProperty("online-mode", "false");
            }
        }
        return ok;
    }

    private static boolean setIntField(Object target, String name, int value) {
        return setField(target, name, Integer.valueOf(value));
    }

    private static boolean setBooleanField(Object target, String name, boolean value) {
        return setField(target, name, Boolean.valueOf(value));
    }

    /** 对非静态 final 实例字段 setAccessible(true)+set 在 JDK8 合法（无需清 modifiers）。 */
    private static boolean setField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                LOGGER.warn("[zstdnet-server] ServerProperties field '{}' not found; cannot override.", name);
                return false;
            }
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[zstdnet-server] failed to override ServerProperties field '{}': {}", name, e.toString());
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        return null;
    }

    /** 取出 {@code Settings.properties}（底层 java.util.Properties 映射）。 */
    private static Properties extractProperties(Object settings) {
        for (Class<?> type = settings.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Properties.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(settings);
                    if (value instanceof Properties) {
                        return (Properties) value;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try next
                }
            }
        }
        LOGGER.debug("[zstdnet-server] Settings.properties map not found; field-only override in effect.");
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
        Path path = Paths.get("server.properties");
        if (!Files.exists(path)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            String lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = new ArrayList<String>(Arrays.asList(text.split("\\R", -1)));
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

            Files.write(path, String.join(lineSeparator, lines).getBytes(StandardCharsets.UTF_8));
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

    private static final class AutoPortConfig {
        private final boolean enabled;
        private final boolean autoTakeover;
        private final String listenHost;
        private final int configuredTargetPort;

        AutoPortConfig(boolean enabled, boolean autoTakeover, String listenHost, int configuredTargetPort) {
            this.enabled = enabled;
            this.autoTakeover = autoTakeover;
            this.listenHost = listenHost;
            this.configuredTargetPort = configuredTargetPort;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AutoPortConfig)) {
                return false;
            }
            AutoPortConfig other = (AutoPortConfig) o;
            return this.enabled == other.enabled
                && this.autoTakeover == other.autoTakeover
                && this.configuredTargetPort == other.configuredTargetPort
                && Objects.equals(this.listenHost, other.listenHost);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, autoTakeover, listenHost, configuredTargetPort);
        }
    }
}
