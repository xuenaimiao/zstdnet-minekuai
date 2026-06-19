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

package cn.tohsaka.factory.zstdnet.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端侧的「正版核验」：登录阶段收到客户端应答后，用同一 {@code serverId} 向会话服务发起
 * {@code hasJoined} 查询，成功则返回真实正版档案（UUID + 皮肤 properties）。
 * <p>
 * 与加载器/版本无关：直接走 HTTPS GET + JSON 解析，<b>不依赖各 MC 版本 {@code MinecraftSessionService} 的签名差异</b>；
 * 返回的 {@link VerifiedProfile} 是纯数据，由各变体的 {@link PremiumProfiles} 转成 {@code com.mojang.authlib.GameProfile}。
 * HTTP 抓取经 {@link SessionFetcher} 注入，便于单测（无需真实联网）。
 */
public final class MojangPremiumVerifier {

    /** Mojang 官方会话服务基址。authlib-injector / Yggdrasil 可通过配置覆盖。 */
    public static final String MOJANG_SESSION_BASE = "https://sessionserver.mojang.com";

    private MojangPremiumVerifier() {
    }

    /**
     * 用 {@code hasJoined} 核验玩家身份。
     *
     * @param sessionBase 会话服务基址（如 {@link #MOJANG_SESSION_BASE}）
     * @param username    LoginStart 中的玩家名（作为查询键）
     * @param serverId    与客户端 {@code joinServer} 一致的 serverId（由 nonce 推导）
     * @param clientIp    客户端真实 IP（可空；非空时作为 {@code ip=} 传给会话服，类似 prevent-proxy-connections）
     * @param fetcher     HTTP 抓取器
     * @return 核验通过返回正版档案；未通过 / 出错返回 {@code null}
     */
    public static VerifiedProfile verify(
        String sessionBase,
        String username,
        String serverId,
        String clientIp,
        SessionFetcher fetcher
    ) {
        if (username == null || username.isBlank() || serverId == null || serverId.isBlank()) {
            return null;
        }
        String base = (sessionBase == null || sessionBase.isBlank()) ? MOJANG_SESSION_BASE : sessionBase.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder url = new StringBuilder(base)
            .append("/session/minecraft/hasJoined?username=")
            .append(URLEncoder.encode(username, StandardCharsets.UTF_8))
            .append("&serverId=")
            .append(URLEncoder.encode(serverId, StandardCharsets.UTF_8));
        if (clientIp != null && !clientIp.isBlank()) {
            url.append("&ip=").append(URLEncoder.encode(clientIp.trim(), StandardCharsets.UTF_8));
        }

        SessionFetcher.Response response;
        try {
            response = fetcher.get(url.toString());
        } catch (Exception e) {
            return null;
        }
        if (response == null || response.status() != 200 || response.body() == null || response.body().isBlank()) {
            // 204 / 空体 = Mojang 未确认该玩家在此 serverId 下完成 join → 未通过。
            return null;
        }
        return parse(response.body());
    }

    /** 解析 {@code hasJoined} 返回的 JSON：{@code {id, name, properties:[{name,value,signature}]}}。 */
    static VerifiedProfile parse(String json) {
        try {
            Object root = MiniJson.parse(json);
            if (!(root instanceof Map<?, ?> obj)) {
                return null;
            }
            Object idValue = obj.get("id");
            Object nameValue = obj.get("name");
            if (!(idValue instanceof String idString) || !(nameValue instanceof String name)) {
                return null;
            }
            UUID uuid = parseUndashedUuid(idString);
            if (uuid == null) {
                return null;
            }

            List<Property> properties = new ArrayList<>();
            if (obj.get("properties") instanceof List<?> arr) {
                for (Object el : arr) {
                    if (!(el instanceof Map<?, ?> p)) {
                        continue;
                    }
                    Object pname = p.get("name");
                    Object value = p.get("value");
                    Object signature = p.get("signature");
                    if (pname instanceof String pn && value instanceof String pv) {
                        properties.add(new Property(pn, pv, signature instanceof String ps ? ps : null));
                    }
                }
            }
            return new VerifiedProfile(uuid, name, List.copyOf(properties));
        } catch (RuntimeException | StackOverflowError e) {
            // 任何畸形会话服响应（含 MiniJson 深嵌套兜底外的 StackOverflowError）均安全归一为「未通过=null」，
            // 使两条登录路线（coremod catch(Throwable) / Fabric CompletableFuture）都走显式失败策略。
            return null;
        }
    }

    /** 把 Mojang 的无连字符 32 位 hex UUID 解析为 {@link UUID}；非法返回 {@code null}。 */
    static UUID parseUndashedUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
        if (hex.length() != 32 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return null;
        }
        String dashed = hex.substring(0, 8) + "-"
            + hex.substring(8, 12) + "-"
            + hex.substring(12, 16) + "-"
            + hex.substring(16, 20) + "-"
            + hex.substring(20);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 默认 HTTP 抓取器（基于 JDK {@link HttpClient}）。 */
    public static SessionFetcher defaultFetcher() {
        return DefaultFetcher.INSTANCE;
    }

    /** 已核验的正版档案（纯数据，不含 authlib 类型，便于单测）。 */
    public record VerifiedProfile(UUID id, String name, List<Property> properties) {
    }

    /** GameProfile 属性（皮肤等），{@code value}/{@code signature} 为 base64。 */
    public record Property(String name, String value, String signature) {
    }

    /** HTTP 抓取器抽象，便于注入测试桩。 */
    @FunctionalInterface
    public interface SessionFetcher {
        Response get(String url) throws Exception;

        record Response(int status, String body) {
        }
    }

    private static final class DefaultFetcher implements SessionFetcher {
        private static final DefaultFetcher INSTANCE = new DefaultFetcher();
        private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

        @Override
        public Response get(String url) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "ZstdNet")
                .GET()
                .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        }
    }
}
