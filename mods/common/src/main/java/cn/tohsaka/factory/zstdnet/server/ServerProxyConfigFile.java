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

import cn.tohsaka.factory.zstdnet.core.compress.DictionaryFiles;
import cn.tohsaka.factory.zstdnet.platform.Platforms;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

public final class ServerProxyConfigFile {
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int DEFAULT_ZSTD_PORT = 25565;
    private static final int DEFAULT_BACKEND_PORT = 25566;
    private static final int DEFAULT_VOICE_CHAT_PORT = 24454;
    private static final int DEFAULT_VOICE_CHAT_LISTEN_PORT = 24455;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final String DEFAULT_TARGET_HOST = "127.0.0.1";
    private static final String DEFAULT_VOICE_CHAT_LISTEN = DEFAULT_LISTEN_HOST + ":" + DEFAULT_VOICE_CHAT_LISTEN_PORT;
    private static final String DEFAULT_TRUSTED_PROXY_IPS = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
    private static final Set<String> KNOWN_KEYS = Set.of(
        "enabled",
        "auto_takeover",
        "listen",
        "target",
        "voice_chat_passthrough",
        "voice_chat_listen",
        "voice_chat_target",
        "level",
        "long_distance_matching",
        "window_log",
        "dictionary",
        "dictionary_auto",
        "dictionary_capture",
        "dictionary_train",
        "max_conn_per_ip",
        "max_req_per_window",
        "request_window",
        "ban_duration",
        "stats_interval",
        "flush_interval",
        "idle_timeout",
        "max_rate_per_conn_bps",
        "max_rate_global_bps",
        "burst_bytes",
        "trust_proxy_protocol",
        "trusted_proxy_ips"
    );

    private ServerProxyConfigFile() {
    }

    public static Path path() {
        return Platforms.get().configDir().resolve("zstdnet-server.properties");
    }

    /** 自动模式下训练产物的固定文件名。 */
    public static final String AUTO_TRAINED_DICT = "trained.dict";

    /**
     * 解析「当前实际生效的字典文件名」：显式 {@code dictionary=} 优先；否则当 {@code dictionary_auto=true}
     * 且已训练出 {@code trained.dict} 时回退到它。两处读字典的地方（运行时压缩 + play 阶段下发）都走这里，避免漂移。
     *
     * @return 字典文件名；都没有时返回空串
     */
    public static String resolveDictionaryName(Properties props, Path configDir) {
        String name = props.getProperty("dictionary", "").trim();
        if (!name.isEmpty()) {
            return name;
        }
        boolean auto = Boolean.parseBoolean(props.getProperty("dictionary_auto", "false").trim());
        if (auto && Files.isRegularFile(DictionaryFiles.dictDir(configDir).resolve(AUTO_TRAINED_DICT))) {
            return AUTO_TRAINED_DICT;
        }
        return "";
    }

    /**
     * 读取服务端当前实际生效的字典字节（供 play 阶段向客户端自动下发）。自动模式下训练完成后会自动返回 {@code trained.dict}。
     *
     * @return 字典字节；未配置或读取失败时返回 null
     */
    public static byte[] loadDictionaryBytes() {
        Path configDir = path().getParent();
        String name = resolveDictionaryName(loadProperties(), configDir);
        if (name.isEmpty()) {
            return null;
        }
        try {
            return DictionaryFiles.load(configDir, name);
        } catch (Exception ex) {
            return null;
        }
    }

    public static int readListenPort() {
        return parsePort(loadProperties().getProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT), DEFAULT_ZSTD_PORT);
    }

    public static int readTargetPort() {
        return parsePort(loadProperties().getProperty("target", "127.0.0.1:" + DEFAULT_BACKEND_PORT), DEFAULT_BACKEND_PORT);
    }

    public static int readVoiceListenPort() {
        return parsePort(loadProperties().getProperty("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN), DEFAULT_VOICE_CHAT_LISTEN_PORT);
    }

    public static int readVoiceTargetPort() {
        return parsePort(loadProperties().getProperty("voice_chat_target", defaultVoiceChatTarget()), defaultVoiceChatTargetPort());
    }

    public static void writeListenPort(int port) throws IOException {
        writePorts(port, null);
    }

    public static void writeTargetPort(int port) throws IOException {
        writePorts(null, port);
    }

    public static void writeVoiceTargetPort(int port) throws IOException {
        writeVoicePorts(null, port);
    }

    public static void writeVoiceListenPort(int port) throws IOException {
        writeVoicePorts(port, null);
    }

    public static void writePorts(Integer listenPort, Integer targetPort) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        String currentListen = props.getProperty("listen", DEFAULT_LISTEN_HOST + ":" + DEFAULT_ZSTD_PORT);
        String currentTarget = props.getProperty("target", DEFAULT_TARGET_HOST + ":" + DEFAULT_BACKEND_PORT);
        String listenHost = parseHost(currentListen, DEFAULT_LISTEN_HOST);
        String targetHost = parseHost(currentTarget, DEFAULT_TARGET_HOST);
        String listenValue = listenHost + ":" + (listenPort != null ? listenPort : parsePort(currentListen, DEFAULT_ZSTD_PORT));
        String targetValue = targetHost + ":" + (targetPort != null ? targetPort : parsePort(currentTarget, DEFAULT_BACKEND_PORT));
        props.setProperty("enabled", "true");
        props.putIfAbsent("auto_takeover", "false");
        props.setProperty("listen", listenValue);
        props.setProperty("target", targetValue);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    public static void writeVoicePorts(Integer listenPort, Integer targetPort) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        String currentListen = props.getProperty("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN);
        String currentTarget = props.getProperty("voice_chat_target", defaultVoiceChatTarget());
        String listenHost = parseHost(currentListen, DEFAULT_LISTEN_HOST);
        String targetHost = parseHost(currentTarget, DEFAULT_TARGET_HOST);
        int resolvedTargetPort = targetPort != null ? targetPort : parsePort(currentTarget, DEFAULT_VOICE_CHAT_PORT);
        int resolvedListenPort = listenPort != null ? listenPort : parsePort(currentListen, DEFAULT_VOICE_CHAT_LISTEN_PORT);
        String listenValue = listenHost + ":" + resolvedListenPort;
        String targetValue = targetHost + ":" + resolvedTargetPort;
        props.setProperty("voice_chat_passthrough", "true");
        props.setProperty("voice_chat_listen", listenValue);
        props.setProperty("voice_chat_target", targetValue);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    public static void writeResolvedAutoTakeoverConfig(
        String listenHost,
        int listenPort,
        int targetPort
    ) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        props.setProperty("enabled", "true");
        props.setProperty("auto_takeover", "true");
        props.setProperty("listen", parseHost(listenHost, DEFAULT_LISTEN_HOST) + ":" + listenPort);
        props.setProperty("target", DEFAULT_TARGET_HOST + ":" + targetPort);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    /**
     * 写入「插件端」默认配置。插件端（Bukkit/Spigot/Paper、混合端）无法像 mod 那样在绑定前挪后端端口，
     * 所以必须 {@code auto_takeover=false}：MC 服务器照常占用 server-port 给原版玩家直连，
     * zstd 代理独占另一个监听端口、把解压后的流量转发回本机后端端口。插件 onEnable 时在配置缺失才调用一次，
     * 之后管理员可自行编辑此文件（带注释模板会保留）。
     *
     * @param listenPort  zstd 代理对外监听端口（需与后端端口不同）
     * @param backendPort 本机 Minecraft 后端端口（通常即 server.properties 的 server-port）
     */
    public static void writePluginDefaults(int listenPort, int backendPort) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        props.setProperty("enabled", "true");
        props.setProperty("auto_takeover", "false");
        props.setProperty("listen", DEFAULT_LISTEN_HOST + ":" + listenPort);
        props.setProperty("target", DEFAULT_TARGET_HOST + ":" + backendPort);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        Path path = path();
        if (!Files.exists(path)) {
            props.setProperty("enabled", "true");
            props.setProperty("auto_takeover", "true");
            props.setProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT);
            props.setProperty("target", "127.0.0.1:" + DEFAULT_BACKEND_PORT);
            props.setProperty("voice_chat_passthrough", "true");
            props.setProperty("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN);
            props.setProperty("voice_chat_target", defaultVoiceChatTarget());
            return props;
        }

        try {
            String text = stripUtf8Bom(Files.readString(path, StandardCharsets.UTF_8));
            try (Reader reader = new StringReader(text)) {
                props.load(reader);
            }
        } catch (IOException ignored) {
        }
        return props;
    }

    private static Properties normalizeProperties(Properties rawProps) {
        Properties props = new Properties();
        if (rawProps != null) {
            props.putAll(rawProps);
        }

        props.remove("allow_raw_login");
        props.putIfAbsent("enabled", "true");
        props.putIfAbsent("auto_takeover", "true");
        props.putIfAbsent("listen", DEFAULT_LISTEN_HOST + ":" + DEFAULT_ZSTD_PORT);
        props.putIfAbsent("target", DEFAULT_TARGET_HOST + ":" + DEFAULT_BACKEND_PORT);
        props.putIfAbsent("voice_chat_passthrough", "true");
        props.putIfAbsent("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN);
        props.putIfAbsent("voice_chat_target", defaultVoiceChatTarget());
        props.putIfAbsent("level", "9");
        props.putIfAbsent("long_distance_matching", "false");
        props.putIfAbsent("window_log", "0");
        props.putIfAbsent("dictionary", "");
        props.putIfAbsent("dictionary_auto", "false");
        props.putIfAbsent("dictionary_capture", "false");
        props.putIfAbsent("dictionary_train", "false");
        props.putIfAbsent("max_conn_per_ip", "9999");
        props.putIfAbsent("max_req_per_window", "50");
        props.putIfAbsent("request_window", "10s");
        props.putIfAbsent("ban_duration", "1m");
        props.putIfAbsent("stats_interval", "0s");
        props.putIfAbsent("flush_interval", "2ms");
        props.putIfAbsent("idle_timeout", "0");
        props.putIfAbsent("max_rate_per_conn_bps", "0");
        props.putIfAbsent("max_rate_global_bps", "0");
        props.putIfAbsent("burst_bytes", "262144");
        props.putIfAbsent("trust_proxy_protocol", "false");
        props.putIfAbsent("trusted_proxy_ips", DEFAULT_TRUSTED_PROXY_IPS);
        return props;
    }

    private static String stripUtf8Bom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static boolean isCommentFragmentKey(String key, String value) {
        String normalizedKey = key.stripLeading();
        if (normalizedKey.startsWith("#")
            || normalizedKey.startsWith("!")
            || normalizedKey.startsWith("\u00EF\u00BB\u00BF#")
            || normalizedKey.startsWith("\u00EF\u00BB\u00BF!")) {
            return true;
        }

        String normalizedValue = value == null ? "" : value.stripLeading();
        return normalizedKey.endsWith("#") && normalizedValue.startsWith("---");
    }

    private static String detectLineSeparator(Path path) throws IOException {
        if (!Files.exists(path)) {
            return System.lineSeparator();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static int defaultVoiceChatTargetPort() {
        return Platforms.get().isClient() ? DEFAULT_MINECRAFT_PORT : DEFAULT_VOICE_CHAT_PORT;
    }

    private static String defaultVoiceChatTarget() {
        return DEFAULT_TARGET_HOST + ":" + defaultVoiceChatTargetPort();
    }

    private static void writeConfigWithComments(Path path, Properties rawProps, String lineSeparator) throws IOException {
        Properties props = normalizeProperties(rawProps);
        StringBuilder builder = new StringBuilder(1024);
        appendLine(builder, "# ------------------------------------------------------------", lineSeparator);
        appendLine(builder, "# zstdnet 内置服务端配置（自动维护）", lineSeparator);
        appendLine(builder, "# 命令改端口和自动接管时，都会按这份带注释模板重新写回。", lineSeparator);
        appendLine(builder, "# 这样即使配置被更新，说明文字也会保留。", lineSeparator);
        appendLine(builder, "# ------------------------------------------------------------", lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 是否启用内置 zstd 代理。", lineSeparator);
        appendLine(builder, "enabled=" + props.getProperty("enabled"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 是否自动接管当前对外公开的游戏端口。", lineSeparator);
        appendLine(builder, "# 专用服开启后，会把 server.properties 的 server-port 作为公网入口，", lineSeparator);
        appendLine(builder, "# 再把真正的后端游戏端口自动挪到别的本地端口。", lineSeparator);
        appendLine(builder, "auto_takeover=" + props.getProperty("auto_takeover"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# zstd 公网监听入口。示例：0.0.0.0:25565", lineSeparator);
        appendLine(builder, "listen=" + props.getProperty("listen"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 后端 Minecraft 目标地址。示例：127.0.0.1:25566", lineSeparator);
        appendLine(builder, "target=" + props.getProperty("target"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 是否透传 Simple Voice Chat 的 UDP。", lineSeparator);
        appendLine(builder, "voice_chat_passthrough=" + props.getProperty("voice_chat_passthrough"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 语音聊天的公网 UDP 入口。留空时，单机 / LAN 会跟随当前开放端口；写了值就按配置走。", lineSeparator);
        appendLine(builder, "# /zstdport zstdvoice 会修改这个值。", lineSeparator);
        appendLine(builder, "voice_chat_listen=" + props.getProperty("voice_chat_listen"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 语音聊天的后端 UDP 目标。留空时，单机 / LAN 会指向当前 LAN 端口；写了值就按配置走。", lineSeparator);
        appendLine(builder, "# 专用服留空时默认指向本机 24454。", lineSeparator);
        appendLine(builder, "# /zstdport voice 会修改这个值。", lineSeparator);
        appendLine(builder, "voice_chat_target=" + props.getProperty("voice_chat_target"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# zstd 压缩等级（1-22，通常建议 3-9）。", lineSeparator);
        appendLine(builder, "level=" + props.getProperty("level"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 长距离匹配（LDM）：面向“同样的大结构在几分钟内反复出现”的高重复场景，", lineSeparator);
        appendLine(builder, "# 用更大的窗口捕捉默认窗口之外的重复，进一步降低带宽。默认关闭。", lineSeparator);
        appendLine(builder, "# 代价：每条连接、每个方向会多吃约 (2^window_log) 字节内存（见下），多人时在服务端累加。", lineSeparator);
        appendLine(builder, "long_distance_matching=" + props.getProperty("long_distance_matching"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# LDM 窗口大小，取 2 的幂的指数：0=保守默认(24≈16MiB)，24≈16MiB，25≈32MiB，27≈128MiB。", lineSeparator);
        appendLine(builder, "# 仅在 long_distance_matching=true 时生效。<=27 的帧任何客户端都能解码，对现有客户端线兼容；", lineSeparator);
        appendLine(builder, "# >27 需要客户端同步开启 long_distance_matching/window_log，否则会解码失败。", lineSeparator);
        appendLine(builder, "window_log=" + props.getProperty("window_log"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# ===== 字典（开启可显著提升压缩率，全部默认关闭）=====", lineSeparator);
        appendLine(builder, "# 【推荐·一键全自动】dictionary_auto=true：服务器自动采样在线流量→样本够了自动后台训练→", lineSeparator);
        appendLine(builder, "# 训练完把字典「热插」启用（不断开任何在线连接），并下发给所有在线+新玩家。全程只改这一行，", lineSeparator);
        appendLine(builder, "# 无需再编辑文件、无需重启、无需手动分发。玩家拿到字典后被提示重连一次即享字典压缩。", lineSeparator);
        appendLine(builder, "# 通常两三名玩家正常进服一次即可达到训练门槛（无需任何人反复进出）；产物为 config/zstdnet/dict/trained.dict。", lineSeparator);
        appendLine(builder, "dictionary_auto=" + props.getProperty("dictionary_auto"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 【手动】指定训练字典文件名（相对 config/zstdnet/dict/ 目录，或绝对路径）。留空=不用字典。", lineSeparator);
        appendLine(builder, "# 显式设置时优先于 dictionary_auto。字典对“登录 registry/tag/recipe 爆发”和“海量小包”提升最大。", lineSeparator);
        appendLine(builder, "# 用了不同字典的客户端会被拒绝；服务端会自动把字典下发给玩家，一般无需手动分发。", lineSeparator);
        appendLine(builder, "dictionary=" + props.getProperty("dictionary"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 【手动制作-采样】开启后把每条连接开头的下行数据采样到 config/zstdnet/dict/samples/，", lineSeparator);
        appendLine(builder, "# 供训练字典使用（有大小上限，制作完请关闭）。dictionary_auto=true 时无需手动开这个。默认关闭。", lineSeparator);
        appendLine(builder, "dictionary_capture=" + props.getProperty("dictionary_capture"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 【手动制作-训练】设为 true 并保存，会用 samples/ 里的语料训练出 dict/trained.dict，", lineSeparator);
        appendLine(builder, "# 然后把 dictionary 改成 trained.dict、本项改回 false 即可启用。dictionary_auto=true 时无需手动开。默认关闭。", lineSeparator);
        appendLine(builder, "dictionary_train=" + props.getProperty("dictionary_train"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 单个 IP 的最大并发连接数。0 表示不限制。", lineSeparator);
        appendLine(builder, "max_conn_per_ip=" + props.getProperty("max_conn_per_ip"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 单个 IP 在 request_window 内允许的最大请求次数。<=0 表示不限制。", lineSeparator);
        appendLine(builder, "max_req_per_window=" + props.getProperty("max_req_per_window"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 请求计数时间窗口。", lineSeparator);
        appendLine(builder, "request_window=" + props.getProperty("request_window"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 超限后的封禁时长。", lineSeparator);
        appendLine(builder, "ban_duration=" + props.getProperty("ban_duration"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 运行时统计日志输出间隔。0 表示关闭周期统计。", lineSeparator);
        appendLine(builder, "stats_interval=" + props.getProperty("stats_interval"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# zstd flush 间隔。0 或 0ms 表示每次写入都 flush。", lineSeparator);
        appendLine(builder, "flush_interval=" + props.getProperty("flush_interval"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 后端空闲超时。0 表示关闭超时。", lineSeparator);
        appendLine(builder, "idle_timeout=" + props.getProperty("idle_timeout"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 单连接带宽限制，单位字节/秒。0 表示不限制。", lineSeparator);
        appendLine(builder, "max_rate_per_conn_bps=" + props.getProperty("max_rate_per_conn_bps"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 全局总带宽限制，单位字节/秒。0 表示不限制。", lineSeparator);
        appendLine(builder, "max_rate_global_bps=" + props.getProperty("max_rate_global_bps"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 令牌桶突发容量，单位字节。", lineSeparator);
        appendLine(builder, "burst_bytes=" + props.getProperty("burst_bytes"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 如果玩家是通过 frp / 反代进服，并且你想让后端看到玩家真实 IP，就改成 true。", lineSeparator);
        appendLine(builder, "# 普通直连、本机测试、局域网、公网直连都保持 false。", lineSeparator);
        appendLine(builder, "# 改成 true 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝。", lineSeparator);
        appendLine(builder, "trust_proxy_protocol=" + props.getProperty("trust_proxy_protocol"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 允许哪些机器转发“玩家真实 IP”给 zstdnet。", lineSeparator);
        appendLine(builder, "# frpc 和服务端在同一台机器时不用改，保持 127.0.0.1 即可。", lineSeparator);
        appendLine(builder, "# 如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 IP。", lineSeparator);
        appendLine(builder, "trusted_proxy_ips=" + props.getProperty("trusted_proxy_ips"), lineSeparator);

        LinkedHashSet<String> extraKeys = new LinkedHashSet<>();
        for (String key : rawProps.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(key) && !"allow_raw_login".equals(key) && !isCommentFragmentKey(key, rawProps.getProperty(key, ""))) {
                extraKeys.add(key);
            }
        }

        if (!extraKeys.isEmpty()) {
            appendLine(builder, "", lineSeparator);
            appendLine(builder, "# 从旧配置中保留下来的额外自定义字段。", lineSeparator);
            for (String key : extraKeys) {
                appendLine(builder, key + "=" + rawProps.getProperty(key, ""), lineSeparator);
            }
        }

        Files.writeString(path, withUtf8Bom(builder.toString()), StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder builder, String line, String lineSeparator) {
        builder.append(line).append(lineSeparator);
    }

    private static String withUtf8Bom(String text) {
        return "\uFEFF" + stripUtf8Bom(text);
    }

    static String parseHost(String listen, String fallback) {
        String raw = listen == null ? "" : listen.trim();
        if (raw.isEmpty()) {
            return fallback;
        }

        if (raw.startsWith("[") && raw.contains("]")) {
            int end = raw.indexOf(']');
            return raw.substring(1, end).trim();
        }

        int idx = raw.lastIndexOf(':');
        if (idx > 0 && raw.indexOf(':') == idx) {
            return raw.substring(0, idx).trim();
        }
        return fallback;
    }

    static int parsePort(String listen, int fallback) {
        String raw = listen == null ? "" : listen.trim();
        try {
            if (raw.startsWith("[") && raw.contains("]")) {
                int end = raw.indexOf(']');
                if (end + 1 < raw.length() && raw.charAt(end + 1) == ':') {
                    return Integer.parseInt(raw.substring(end + 2).trim());
                }
                return fallback;
            }

            int idx = raw.lastIndexOf(':');
            if (idx > 0 && raw.indexOf(':') == idx) {
                return Integer.parseInt(raw.substring(idx + 1).trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

}
