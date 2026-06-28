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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
        if (username == null || username.trim().isEmpty() || serverId == null || serverId.trim().isEmpty()) {
            return null;
        }
        String base = (sessionBase == null || sessionBase.trim().isEmpty()) ? MOJANG_SESSION_BASE : sessionBase.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder url = new StringBuilder(base)
            .append("/session/minecraft/hasJoined?username=")
            .append(encodeUtf8(username))
            .append("&serverId=")
            .append(encodeUtf8(serverId));
        if (clientIp != null && !clientIp.trim().isEmpty()) {
            url.append("&ip=").append(encodeUtf8(clientIp.trim()));
        }

        SessionFetcher.Response response;
        try {
            response = fetcher.get(url.toString());
        } catch (Exception e) {
            return null;
        }
        if (response == null || response.status() != 200 || response.body() == null || response.body().trim().isEmpty()) {
            // 204 / 空体 = Mojang 未确认该玩家在此 serverId 下完成 join → 未通过。
            return null;
        }
        return parse(response.body());
    }

    /** UTF-8 URL 编码（UTF-8 始终可用，理论不可达异常归一为原串）。 */
    private static String encodeUtf8(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /** 解析 {@code hasJoined} 返回的 JSON：{@code {id, name, properties:[{name,value,signature}]}}。 */
    static VerifiedProfile parse(String json) {
        try {
            Object root = MiniJson.parse(json);
            if (!(root instanceof Map<?, ?>)) {
                return null;
            }
            Map<?, ?> obj = (Map<?, ?>) root;
            Object idValue = obj.get("id");
            Object nameValue = obj.get("name");
            if (!(idValue instanceof String) || !(nameValue instanceof String)) {
                return null;
            }
            String idString = (String) idValue;
            String name = (String) nameValue;
            UUID uuid = parseUndashedUuid(idString);
            if (uuid == null) {
                return null;
            }

            List<Property> properties = new ArrayList<>();
            Object propertiesValue = obj.get("properties");
            if (propertiesValue instanceof List<?>) {
                List<?> arr = (List<?>) propertiesValue;
                for (Object el : arr) {
                    if (!(el instanceof Map<?, ?>)) {
                        continue;
                    }
                    Map<?, ?> p = (Map<?, ?>) el;
                    Object pname = p.get("name");
                    Object value = p.get("value");
                    Object signature = p.get("signature");
                    if (pname instanceof String && value instanceof String) {
                        properties.add(new Property((String) pname, (String) value, signature instanceof String ? (String) signature : null));
                    }
                }
            }
            return new VerifiedProfile(uuid, name, Collections.unmodifiableList(new ArrayList<>(properties)));
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

    /** 默认 HTTP 抓取器（基于 JDK {@link HttpURLConnection}）。 */
    public static SessionFetcher defaultFetcher() {
        return DefaultFetcher.INSTANCE;
    }

    /** 已核验的正版档案（纯数据，不含 authlib 类型，便于单测）。 */
    public static final class VerifiedProfile {
        private final UUID id;
        private final String name;
        private final List<Property> properties;

        public VerifiedProfile(UUID id, String name, List<Property> properties) {
            this.id = id;
            this.name = name;
            this.properties = properties;
        }

        public UUID id() {
            return this.id;
        }

        public String name() {
            return this.name;
        }

        public List<Property> properties() {
            return this.properties;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof VerifiedProfile)) {
                return false;
            }
            VerifiedProfile other = (VerifiedProfile) o;
            return Objects.equals(this.id, other.id)
                && Objects.equals(this.name, other.name)
                && Objects.equals(this.properties, other.properties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.id, this.name, this.properties);
        }

        @Override
        public String toString() {
            return "VerifiedProfile[id=" + this.id + ", name=" + this.name + ", properties=" + this.properties + "]";
        }
    }

    /** GameProfile 属性（皮肤等），{@code value}/{@code signature} 为 base64。 */
    public static final class Property {
        private final String name;
        private final String value;
        private final String signature;

        public Property(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }

        public String name() {
            return this.name;
        }

        public String value() {
            return this.value;
        }

        public String signature() {
            return this.signature;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Property)) {
                return false;
            }
            Property other = (Property) o;
            return Objects.equals(this.name, other.name)
                && Objects.equals(this.value, other.value)
                && Objects.equals(this.signature, other.signature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.name, this.value, this.signature);
        }

        @Override
        public String toString() {
            return "Property[name=" + this.name + ", value=" + this.value + ", signature=" + this.signature + "]";
        }
    }

    /** HTTP 抓取器抽象，便于注入测试桩。 */
    @FunctionalInterface
    public interface SessionFetcher {
        Response get(String url) throws Exception;

        final class Response {
            private final int status;
            private final String body;

            public Response(int status, String body) {
                this.status = status;
                this.body = body;
            }

            public int status() {
                return this.status;
            }

            public String body() {
                return this.body;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof Response)) {
                    return false;
                }
                Response other = (Response) o;
                return this.status == other.status
                    && Objects.equals(this.body, other.body);
            }

            @Override
            public int hashCode() {
                return Objects.hash(this.status, this.body);
            }

            @Override
            public String toString() {
                return "Response[status=" + this.status + ", body=" + this.body + "]";
            }
        }
    }

    private static final class DefaultFetcher implements SessionFetcher {
        private static final DefaultFetcher INSTANCE = new DefaultFetcher();

        @Override
        public Response get(String url) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "ZstdNet");
            try {
                int status = conn.getResponseCode();
                InputStream stream = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
                String body = readBody(stream);
                return new Response(status, body);
            } finally {
                conn.disconnect();
            }
        }

        /** 读尽响应体并按 UTF-8 解码；{@code null} 流（如部分错误响应）视作空体。 */
        private static String readBody(InputStream stream) throws IOException {
            if (stream == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
            } finally {
                stream.close();
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
