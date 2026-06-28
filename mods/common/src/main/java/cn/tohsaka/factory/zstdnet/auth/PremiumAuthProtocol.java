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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * 「登录阶段正版验证」的线格式与协商常量（与加载器无关，放在 common 单一真源）。
 * <p>
 * 机制（仿 TrueUUID，<b>不走原版加密握手</b>，故全程明文 → ZSTD 压缩照常）：
 * 登录阶段、在 LoginStart 之后、LoginSuccess 之前，服务端在信道 {@code zstdnet:auth} 上发一条 login 自定义查询，
 * 携带一次性 nonce；客户端据 nonce 推导出 {@code serverId} 并本地调用 Mojang 会话服务 {@code joinServer}
 * （access token 不出客户端），回包告知是否完成；服务端再用同一 {@code serverId} 调 {@code hasJoined} 核验、
 * 拿到真实正版 UUID/皮肤并替换登录档案。
 * <p>
 * 客户端 {@code joinServer} 与服务端 {@code hasJoined} 用的 {@code serverId} 必须<b>逐字节一致</b>：
 * 两端都由同一份 nonce 经 {@link #serverIdFromNonce(byte[])}（小写 hex）推导，避免漂移。
 */
public final class PremiumAuthProtocol {

    /** login 自定义查询信道（各变体据此构造 {@code ResourceLocation}）。 */
    public static final String CHANNEL_NAMESPACE = "zstdnet";
    public static final String CHANNEL_PATH = "auth";

    /** 线格式版本，便于将来演进。 */
    public static final byte WIRE_VERSION = 1;

    /** nonce 字节数（128 位足够防碰撞/重放）。 */
    public static final int NONCE_BYTES = 16;

    /**
     * Forge/NeoForge <b>coremod 路线</b>专用的固定 login 自定义查询事务号（魔数 {@code "zstd"}）。
     * <p>
     * Forge 用顺序递增的小整数作为自己的 login 包索引；本协议每个连接只发一条查询，用一个高位魔数避开冲突，
     * 服务端据此识别「这是我方查询的应答」（见各变体 {@code coremod/PremiumAuthServerHooks}）。
     */
    public static final int COREMOD_TRANSACTION_ID = 0x7A737464; // 'z''s''t''d'

    private PremiumAuthProtocol() {
    }

    /**
     * Forge/NeoForge coremod 路线：把 {@code serverId} 编码进 login 自定义查询信道的 {@code ResourceLocation} 路径。
     * <p>
     * <b>为什么走路径而非 payload</b>：现代 MC（1.20.2+，即 1.21.1/26.1）在解码 login 自定义查询时会把 payload
     * 字节直接丢弃（{@code DiscardedQueryPayload}/{@code DiscardedQueryAnswerPayload}），故 nonce 无法靠 payload 过河；
     * 而信道的 {@code ResourceLocation}（含 namespace+path）始终随包读出。{@code serverId} 即 {@code hex(nonce)}，
     * 恰好是 {@code ResourceLocation} 路径的合法字符（{@code [a-z0-9/._-]}），可直接拼进路径。
     * <p>
     * 返回形如 {@code "auth/<hex>"} 的路径（namespace 固定为 {@link #CHANNEL_NAMESPACE}）。
     * Fabric 路线仍走 fabric-api 的 payload（其网络层会保留字节），不受此影响。
     */
    public static String channelPathWithServerId(String serverId) {
        if (serverId == null || serverId.trim().isEmpty()) {
            throw new IllegalArgumentException("blank serverId");
        }
        return CHANNEL_PATH + "/" + serverId;
    }

    /**
     * 从 login 自定义查询信道路径解析出 {@code serverId}；非本协议路径或非法字符返回 {@code null}。
     * <p>
     * 两端一致性由此保证：客户端从路径取出的 {@code serverId} 与服务端 {@link #serverIdFromNonce(byte[])}
     * 生成的逐字节相同（同为小写 hex）。
     */
    public static String serverIdFromChannelPath(String path) {
        if (path == null) {
            return null;
        }
        String prefix = CHANNEL_PATH + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String id = path.substring(prefix.length());
        if (id.isEmpty() || id.length() > 128) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return id;
    }

    /** 服务端→客户端的挑战包：{@code [wireVersion][nonceLen][nonce...]}。 */
    public static byte[] encodeChallenge(byte[] nonce) {
        if (nonce == null || nonce.length == 0 || nonce.length > 64) {
            throw new IllegalArgumentException("invalid nonce length: " + (nonce == null ? -1 : nonce.length));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(2 + nonce.length);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(WIRE_VERSION);
            out.writeByte(nonce.length);
            out.write(nonce);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * 解析挑战包，返回 nonce；版本不符或格式损坏返回 {@code null}。
     */
    public static byte[] decodeChallenge(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte version = in.readByte();
            if (version != WIRE_VERSION) {
                return null;
            }
            int len = in.readUnsignedByte();
            if (len <= 0 || len > 64) {
                return null;
            }
            byte[] nonce = new byte[len];
            in.readFully(nonce);
            return nonce;
        } catch (IOException e) {
            return null;
        }
    }

    /** 由 nonce 确定性推导出 {@code serverId}（小写 hex），两端一致。 */
    public static String serverIdFromNonce(byte[] nonce) {
        final char[] hexDigits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(nonce.length * 2);
        for (byte b : nonce) {
            int v = b & 0xFF;
            sb.append(hexDigits[v >>> 4]);
            sb.append(hexDigits[v & 0x0F]);
        }
        return sb.toString();
    }

    /** 客户端→服务端的应答包：{@code [wireVersion][authenticated][sourceUtf]}。 */
    public static byte[] encodeAnswer(boolean authenticated, String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(WIRE_VERSION);
            out.writeBoolean(authenticated);
            out.writeUTF(source == null ? "" : source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** 解析应答包；版本不符或格式损坏返回未验证应答。 */
    public static Answer decodeAnswer(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return Answer.UNAUTHENTICATED;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte version = in.readByte();
            if (version != WIRE_VERSION) {
                return Answer.UNAUTHENTICATED;
            }
            boolean authenticated = in.readBoolean();
            String source = in.readUTF();
            return new Answer(authenticated, source);
        } catch (IOException e) {
            return Answer.UNAUTHENTICATED;
        }
    }

    /**
     * 客户端应答内容。
     *
     * @param authenticated 客户端是否成功完成 {@code joinServer}（即拥有有效正版会话）
     * @param source        验证来源标识（如 {@code mojang} / {@code yggdrasil}），仅用于日志/诊断
     */
    public static final class Answer {
        private final boolean authenticated;
        private final String source;

        public Answer(boolean authenticated, String source) {
            this.authenticated = authenticated;
            this.source = source;
        }

        public boolean authenticated() {
            return this.authenticated;
        }

        public String source() {
            return this.source;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Answer)) {
                return false;
            }
            Answer other = (Answer) o;
            return this.authenticated == other.authenticated && Objects.equals(this.source, other.source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.authenticated, this.source);
        }

        @Override
        public String toString() {
            return "Answer[authenticated=" + this.authenticated + ", source=" + this.source + "]";
        }

        public static final Answer UNAUTHENTICATED = new Answer(false, "");
    }
}
