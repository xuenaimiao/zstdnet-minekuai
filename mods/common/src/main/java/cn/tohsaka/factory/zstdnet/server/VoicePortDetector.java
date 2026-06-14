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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 语音端口探测模块（零配置兼容各类语音 mod 的“服务端探测”一环）。
 *
 * <p>它扫描后端服务器上常见语音 mod 的配置文件，找出它们监听的 <b>独立 UDP 端口</b>，
 * 供 ZstdNet 把这些端口纳入「单端口隧道」或「直连桥接」处理（见 {@code ServerProxyRuntime}
 * 与客户端 {@code LocalZstdNet}）。探测结果是有序去重的端口列表，列表 <b>下标即 channelId</b>，
 * 客户端与服务端必须按同一顺序约定 channelId，因此本类对外只暴露这一个有序入口。</p>
 *
 * <p>判定原则：</p>
 * <ul>
 *   <li><b>同端口语音</b>（SVC {@code port=-1}、Plasmo {@code [host].port=0}）会跟随后端游戏端口，
 *       其 UDP 已由内置 game 直通覆盖，<b>不需要单独通道</b>，这里直接跳过。</li>
 *   <li><b>独立端口语音</b>（SVC 默认 24454、Plasmo 显式端口、{@code extra_udp_ports} 兜底）才产出一条端口。</li>
 *   <li>若管理员显式把语音 mod 的「公网地址」写成了具体公网 IP（SVC {@code voice_host}、Plasmo
 *       {@code [host.public].ip}），客户端会绕过 {@code 127.0.0.1} 直连该公网地址，ZstdNet 无法拦截，
 *       此时记一条 WARN 并跳过——这是用户主动要求的直连，与「经 ZstdNet 中转」互斥。</li>
 * </ul>
 *
 * <p>纯逻辑、不依赖 Minecraft 类，便于单元测试：文件 IO 与字符串解析拆开，解析方法均为包级静态。</p>
 */
public final class VoicePortDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoicePortDetector.class);
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private VoicePortDetector() {
    }

    /** 一条被探测到、需要单独通道/桥接的语音端口。{@code label} 仅用于日志诊断。 */
    public record VoicePort(String label, int port) {
    }

    /**
     * 扫描后端常见语音 mod，产出需要单独处理的语音端口（有序去重，下标即 channelId）。
     *
     * @param configDir       Minecraft 的 {@code config/} 目录
     * @param backendGamePort 后端游戏端口（auto_takeover 后是被挪走的真实端口）；
     *                        等于该端口的语音端口属「同端口」，已由 game 直通覆盖，跳过
     * @param extraUdpPortsCsv {@code extra_udp_ports} 原始值（逗号分隔，可空）——任意 UDP mod 的通用兜底
     * @return 有序去重的语音端口列表；探测不到时返回空列表（不会为 null）
     */
    public static List<VoicePort> detect(Path configDir, int backendGamePort, String extraUdpPortsCsv) {
        List<VoicePort> raw = new ArrayList<>();

        Integer svc = detectSimpleVoiceChat(configDir);
        if (svc != null) {
            raw.add(new VoicePort("simple_voice_chat", svc));
        }

        Integer plasmo = detectPlasmoVoice(configDir, backendGamePort);
        if (plasmo != null) {
            raw.add(new VoicePort("plasmo_voice", plasmo));
        }

        for (int port : parseExtraPorts(extraUdpPortsCsv)) {
            raw.add(new VoicePort("extra:" + port, port));
        }

        // 去重（按端口）+ 跳过「同端口」（== 后端游戏端口，已由 game 直通覆盖）。保持首次出现顺序。
        List<VoicePort> result = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (VoicePort vp : raw) {
            if (vp.port() == backendGamePort) {
                continue;
            }
            if (seen.add(vp.port())) {
                result.add(vp);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Simple Voice Chat: config/voicechat/voicechat-server.properties
    // ---------------------------------------------------------------------

    private static Integer detectSimpleVoiceChat(Path configDir) {
        Path path = configDir.resolve("voicechat").resolve("voicechat-server.properties");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return parseSimpleVoiceChatPort(text, path.toString());
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed reading Simple Voice Chat config {}: {}", path, e.toString());
            return null;
        }
    }

    /**
     * 从 SVC {@code voicechat-server.properties} 文本解析出需要单独通道的端口。
     *
     * @return 独立 UDP 端口；{@code port=-1}（同端口/默认跟随）、缺失、非法、或 {@code voice_host} 非空时返回 null
     */
    static Integer parseSimpleVoiceChatPort(String propertiesText, String sourceForLog) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(stripBom(propertiesText)));
        } catch (IOException e) {
            return null;
        }

        String voiceHost = trimOrEmpty(props.getProperty("voice_host"));
        if (!voiceHost.isEmpty()) {
            LOGGER.warn(
                "[zstdnet-server] Simple Voice Chat voice_host='{}' is set in {}; clients will connect to that public address "
                    + "directly and bypass ZstdNet voice tunneling. Leave voice_host blank to let ZstdNet handle voice.",
                voiceHost,
                sourceForLog
            );
            return null;
        }

        Integer port = parsePortValue(props.getProperty("port"));
        if (port == null) {
            return null;
        }
        if (port == -1) {
            // 跟随游戏端口（same-port），已由内置 game 直通覆盖。
            return null;
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            LOGGER.warn("[zstdnet-server] invalid Simple Voice Chat port '{}' in {}", port, sourceForLog);
            return null;
        }
        return port;
    }

    // ---------------------------------------------------------------------
    // Plasmo Voice: config/plasmovoice/config.toml
    //   [host]            port = 0            (0 = 跟随 MC server-port)
    //   [host.public]     ip = "0.0.0.0"      (非 0.0.0.0/空 = 显式公网地址，绕过)
    //                     port = 0            (>0 时覆盖 [host].port 对客户端公布的端口)
    // ---------------------------------------------------------------------

    private static Integer detectPlasmoVoice(Path configDir, int backendGamePort) {
        Path path = configDir.resolve("plasmovoice").resolve("config.toml");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return parsePlasmoVoicePort(text, backendGamePort, path.toString());
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed reading Plasmo Voice config {}: {}", path, e.toString());
            return null;
        }
    }

    /**
     * 从 Plasmo {@code config.toml} 文本解析出需要单独通道的端口。
     *
     * @param backendGamePort {@code [host].port=0} 时跟随的后端游戏端口
     * @return 独立 UDP 端口；公网 ip 非默认、端口跟随游戏端口（same-port）、或解析失败时返回 null
     */
    static Integer parsePlasmoVoicePort(String tomlText, int backendGamePort, String sourceForLog) {
        String publicIp = trimQuotes(readTomlValue(tomlText, "host.public", "ip"));
        if (!publicIp.isEmpty() && !"0.0.0.0".equals(publicIp) && !"::".equals(publicIp)) {
            LOGGER.warn(
                "[zstdnet-server] Plasmo Voice [host.public].ip='{}' is set in {}; clients will connect to that public address "
                    + "directly and bypass ZstdNet voice tunneling. Leave it 0.0.0.0 to let ZstdNet handle voice.",
                publicIp,
                sourceForLog
            );
            return null;
        }

        // 对客户端公布的端口：优先 [host.public].port（>0），否则 [host].port，0 表示跟随后端游戏端口。
        Integer publicPort = parsePortValue(trimQuotes(readTomlValue(tomlText, "host.public", "port")));
        Integer hostPort = parsePortValue(trimQuotes(readTomlValue(tomlText, "host", "port")));

        int effective;
        if (publicPort != null && publicPort > 0) {
            effective = publicPort;
        } else if (hostPort != null && hostPort > 0) {
            effective = hostPort;
        } else {
            // port = 0（或缺失）→ 跟随后端游戏端口，属同端口，已由 game 直通覆盖。
            return null;
        }
        if (effective < MIN_PORT || effective > MAX_PORT) {
            LOGGER.warn("[zstdnet-server] invalid Plasmo Voice port '{}' in {}", effective, sourceForLog);
            return null;
        }
        return effective;
    }

    /**
     * 极简 TOML 取值：读取 {@code [section]} 下的 {@code key}。只支持本类需要的两节（host / host.public）、
     * 单行 {@code key = value}，不解析数组/内联表/多行字符串——够用且零依赖。section 精确匹配（不含子表混淆）。
     */
    static String readTomlValue(String tomlText, String section, String key) {
        if (tomlText == null) {
            return "";
        }
        String current = "";
        for (String rawLine : tomlText.split("\\R", -1)) {
            String line = stripTomlComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (!current.equals(section)) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            if (k.equals(key)) {
                return line.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private static String stripTomlComment(String line) {
        // 不处理字符串内的 '#'——本类只读端口/ip 这类简单标量，够用。
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    // ---------------------------------------------------------------------
    // extra_udp_ports = "24454, 30000"  通用兜底
    // ---------------------------------------------------------------------

    static List<Integer> parseExtraPorts(String csv) {
        List<Integer> ports = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ports;
        }
        for (String token : csv.split("[,\\s]+")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            Integer port = parsePortValue(t);
            if (port == null || port < MIN_PORT || port > MAX_PORT) {
                LOGGER.warn("[zstdnet-server] ignoring invalid extra_udp_ports entry '{}'", t);
                continue;
            }
            ports.add(port);
        }
        return ports;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Integer parsePortValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimOrEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String trimQuotes(String raw) {
        String v = trimOrEmpty(raw);
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
            char q = v.charAt(0);
            if (v.charAt(v.length() - 1) == q) {
                return v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static String stripBom(String text) {
        if (text == null) {
            return "";
        }
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }
}
