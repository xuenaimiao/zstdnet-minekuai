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

package cn.tohsaka.factory.zstdnet.auth;

import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.VerifiedProfile;
import com.velocitypowered.api.util.GameProfile;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Velocity 端「登录阶段正版验证」的纯决策逻辑（无 I/O、无全局状态，全部可单测）。
 * <p>
 * {@link VelocityPremiumAuth} 只负责把 Velocity 登录事件接到这里的判定上；判定本身——
 * 是否启用、哪些连接算「经 ZstdNet 进来的」、要发哪几条 login 查询、如何解读应答、
 * 核验结果如何落到 放行正版 / 宽松离线 / 拒绝 三分支、正版档案怎么映射成 Velocity 的
 * {@link GameProfile}——都集中在本类。
 *
 * <h2>客户端兼容：为什么同一个 nonce 发两条查询</h2>
 * 已发布的 ZstdNet 客户端有两条互不相识的验证路线，服务端无法事先分辨（Fabric 两代都不带握手标记）：
 * <ul>
 *   <li><b>信道路径路线</b>（{@link Route#PATH}）：信道 {@code zstdnet:auth/<serverId>}、payload 为空。
 *       Forge 1.18.2/1.19.2/1.20.1、NeoForge 1.20.1/1.21.1/1.21.11/26.x 的 coremod/mixin 钩子，以及
 *       1.4.2-m2.6 起 mixin 驱动的全部 Fabric 变体都走这条。客户端只认信道、<b>不校验事务号</b>，
 *       {@code joinServer} 完成后用原事务号回一条空应答——故 Velocity 自行分配的 message id 不影响。</li>
 *   <li><b>payload 路线</b>（{@link Route#LEGACY}）：信道恰为 {@code zstdnet:auth}、payload 为
 *       {@link PremiumAuthProtocol#encodeChallenge}。1.4.2-m2.5 及更早的 Fabric 变体经 fabric-api
 *       {@code ClientLoginNetworking} 处理，应答带 {@link PremiumAuthProtocol#encodeAnswer} 负载。</li>
 * </ul>
 * 两条查询用<b>同一个 nonce</b>（即同一个 {@code serverId}）：任何客户端只认其中一条，另一条按原版语义回
 * 「未理解」；万一将来某客户端两条都认，两次 {@code joinServer} 用的也是同一个 {@code serverId}，
 * {@code hasJoined} 照样通过。服务端不依赖应答内容（路径路线的应答本就是空的），两条都答完后以
 * {@code hasJoined} 核验结果为唯一真源——与各 mod 变体的现代路线一致。
 */
final class VelocityPremiumLogic {

    /**
     * 能在 Velocity 上走本验证的最低客户端协议号：1.18.2（758）。
     * <p>
     * 已发布的 ZstdNet 变体中，最老的带正版验证客户端钩子的是 Forge 1.18.2；Forge 1.16.5（754）从未实现该钩子。
     * 对更老协议的 ZstdNet 连接若照常强制离线，必然核验失败 → 宽松模式下静默降级成离线身份。故干脆不接管，
     * 交还 Velocity 原有认证（online-mode 下即原版加密认证：身份不丢，只是这条连接压缩收益归零）。
     * 同时也天然满足 Velocity「login 插件消息仅限 1.13+ 客户端」的硬限制。
     */
    static final int MIN_PROTOCOL = 758;

    /** ZstdNet 服务端在握手 host 末尾追加的真实 IP 标记前缀（{@code \0zstdnet-real-ip=<base64url>}）。 */
    static final String REAL_IP_MARKER = "zstdnet-real-ip=";

    /** Velocity 读取的 hasJoined 地址系统属性（见 {@code InitialLoginSessionHandler}），及其默认值。 */
    static final String VELOCITY_SESSION_PROPERTY = "mojang.sessionserver";
    static final String VELOCITY_DEFAULT_HAS_JOINED =
        MojangPremiumVerifier.MOJANG_SESSION_BASE + "/session/minecraft/hasJoined";

    private static final String HAS_JOINED_SUFFIX = "/session/minecraft/hasJoined";

    private VelocityPremiumLogic() {
    }

    // ---- 启用判定 ----

    /** 启动期三种结局。 */
    enum StartupMode {
        /** 开启：ZstdNet 连接强制离线 + zstdnet:auth 带外验证。 */
        ENABLED,
        /** 关闭，且 Velocity 本就离线：纯离线，无需提示。 */
        DISABLED,
        /** 关闭，但 Velocity 在线：ZstdNet 连接会走原版加密 → 压缩收益归零，需一次 WARN。 */
        DISABLED_ENCRYPTED
    }

    /**
     * {@code premium_verification}（auto/on/off）+ Velocity {@code online-mode} → 启动结局。
     * {@code auto} 跟随 Velocity 的 {@code online-mode}（语义同 mod 变体跟随 {@code server.properties}）。
     */
    static StartupMode startupMode(String premiumVerification, boolean velocityOnlineMode) {
        if (PremiumAuthState.resolveEnabled(premiumVerification, velocityOnlineMode)) {
            return StartupMode.ENABLED;
        }
        return velocityOnlineMode ? StartupMode.DISABLED_ENCRYPTED : StartupMode.DISABLED;
    }

    /**
     * 计算交给 {@link PremiumAuthState#configure} 的会话服配置串。
     * <ul>
     *   <li>{@code premium_session_server} 显式配置：原样使用（完全尊重管理员）。</li>
     *   <li>留空，但 Velocity 用 {@code -Dmojang.sessionserver=...} 指向了非官方会话服（Velocity 接外置登录的
     *       官方做法）：取其基址 + {@code mojang}，即「皮肤站 → Mojang 官方」两级核验，与 common 对
     *       authlib-injector 的自动探测同一策略——免得原版玩家在 Velocity 上能过、ZstdNet 玩家却核验不过。</li>
     *   <li>其余：返回空串，交给 common 的默认逻辑（authlib-injector 自动探测 → Mojang 官方）。</li>
     * </ul>
     */
    static String effectiveSessionServerConfig(String configured, String velocityHasJoinedProperty) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String base = sessionBaseFromHasJoinedUrl(velocityHasJoinedProperty);
        if (base == null || base.equalsIgnoreCase(MojangPremiumVerifier.MOJANG_SESSION_BASE)) {
            return "";
        }
        return base + ",mojang";
    }

    /** 从完整 hasJoined 地址反推会话服基址；无法识别的格式返回 {@code null}（不猜）。 */
    static String sessionBaseFromHasJoinedUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String value = url.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        value = stripTrailingSlashes(value);
        if (!value.toLowerCase(Locale.ROOT).endsWith(HAS_JOINED_SUFFIX.toLowerCase(Locale.ROOT))) {
            return null;
        }
        value = stripTrailingSlashes(value.substring(0, value.length() - HAS_JOINED_SUFFIX.length()));
        return value.isEmpty() ? null : value;
    }

    // ---- 连接判定 ----

    /**
     * 这条 Velocity 入站连接是否经 ZstdNet 代理进来（只有这些连接才接管）。
     * <ul>
     *   <li>登记表命中（管线注入器已识别）→ 是。</li>
     *   <li>注入器已挂上但未登记 → 否（原版直连，保持 Velocity 原有认证）。</li>
     *   <li><b>注入器未挂上时的降级</b>：此时 ZstdNet 服务端追加的 {@code zstdnet-real-ip=} 标记不会被剥离，
     *       会原样留在 Velocity 的 raw virtual host 里；故「远端为回环 且 raw host 带该标记」即判为 ZstdNet
     *       （回环信任规则与注入器、Bukkit 插件、mod 变体的 {@code isTrustedLocalProxy} 一致）。
     *       <b>不</b>单凭回环地址判定：FRP 等把公网玩家经本机回环转给 Velocity 的部署下，那会把原版正版玩家
     *       误判进来、强制离线后静默丢失正版身份。判不出来（漏判）则安全地退回 Velocity 原有认证。</li>
     * </ul>
     */
    static boolean isZstdnetConnection(boolean injectorActive, boolean registered, InetAddress remote, String rawHost) {
        if (registered) {
            return true;
        }
        if (injectorActive) {
            return false;
        }
        return remote != null && remote.isLoopbackAddress() && hasRealIpMarker(rawHost);
    }

    /** raw host 是否带 ZstdNet 真实 IP 标记（{@code \0zstdnet-real-ip=...} 段）。 */
    static boolean hasRealIpMarker(String rawHost) {
        return markerValue(rawHost) != null;
    }

    /**
     * 从 raw host 的 {@code zstdnet-real-ip=<base64url>} 段解出玩家真实 IP；无标记或内容非法返回 {@code null}。
     * 只接受 IP 字面量字符（不触发 DNS）。
     */
    static String realIpFromRawHost(String rawHost) {
        String encoded = markerValue(rawHost);
        if (encoded == null) {
            return null;
        }
        String ip;
        try {
            ip = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (ip.isEmpty() || ip.length() > 64) {
            return null;
        }
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == '.' || c == ':' || c == '%';
            if (!ok) {
                return null;
            }
        }
        return ip;
    }

    private static String markerValue(String rawHost) {
        if (rawHost == null || rawHost.indexOf('\0') < 0) {
            return null;
        }
        for (String part : rawHost.split("\0")) {
            if (part.startsWith(REAL_IP_MARKER) && part.length() > REAL_IP_MARKER.length()) {
                return part.substring(REAL_IP_MARKER.length());
            }
        }
        return null;
    }

    /**
     * {@code premium_pass_real_ip} 时交给会话服的玩家 IP：优先注入器登记的真实 IP，其次 raw host 标记（降级模式）；
     * 都没有则 {@code null}（不带 {@code ip=}，比误传回环地址安全）。
     */
    static String clientIpForVerification(String registeredRealIp, String rawHost) {
        if (registeredRealIp != null && !registeredRealIp.trim().isEmpty()) {
            return registeredRealIp.trim();
        }
        return realIpFromRawHost(rawHost);
    }

    /** {@code PreLoginEvent} 里对一条 ZstdNet 连接的处置。 */
    enum PreLoginAction {
        /** 强制离线 + 发 zstdnet:auth 查询。 */
        INTERCEPT,
        /** 已被其他插件拒绝：不插手。 */
        SKIP_DENIED,
        /** 连接对象不支持发送 login 插件消息（非 {@code LoginPhaseConnection}）：不插手。 */
        SKIP_NOT_LOGIN_PHASE,
        /** 客户端协议低于 {@link #MIN_PROTOCOL}：没有能响应的 ZstdNet 客户端，交还 Velocity 原有认证。 */
        SKIP_PROTOCOL_UNSUPPORTED,
        /** 其他插件已显式选了在线/离线（FastLogin / LimboAuth 等）：尊重其决定，不覆盖。 */
        SKIP_OTHER_PLUGIN_DECIDED
    }

    static PreLoginAction preLoginAction(
        int protocol,
        boolean allowed,
        boolean forceOnline,
        boolean forceOffline,
        boolean loginPhaseConnection
    ) {
        if (!allowed) {
            return PreLoginAction.SKIP_DENIED;
        }
        if (!loginPhaseConnection) {
            return PreLoginAction.SKIP_NOT_LOGIN_PHASE;
        }
        if (protocol < MIN_PROTOCOL) {
            return PreLoginAction.SKIP_PROTOCOL_UNSUPPORTED;
        }
        if (forceOnline || forceOffline) {
            return PreLoginAction.SKIP_OTHER_PLUGIN_DECIDED;
        }
        return PreLoginAction.INTERCEPT;
    }

    // ---- 查询与应答 ----

    /** 两条验证路线（见类注释）。 */
    enum Route {
        PATH,
        LEGACY
    }

    /**
     * 一条待发送的 login 查询。
     *
     * @param route     路线
     * @param namespace 信道 namespace
     * @param name      信道 path（Velocity {@code MinecraftChannelIdentifier} 允许 {@code [a-z0-9/._-]}）
     * @param content   payload
     */
    record Challenge(Route route, String namespace, String name, byte[] content) {
        String channelId() {
            return namespace + ":" + name;
        }
    }

    /** 同一 nonce 生成两条查询：先路径路线，后 payload 路线。 */
    static List<Challenge> challenges(byte[] nonce) {
        String serverId = PremiumAuthProtocol.serverIdFromNonce(nonce);
        List<Challenge> list = new ArrayList<>(2);
        list.add(new Challenge(Route.PATH, PremiumAuthProtocol.CHANNEL_NAMESPACE,
            PremiumAuthProtocol.channelPathWithServerId(serverId), new byte[0]));
        list.add(new Challenge(Route.LEGACY, PremiumAuthProtocol.CHANNEL_NAMESPACE,
            PremiumAuthProtocol.CHANNEL_PATH, PremiumAuthProtocol.encodeChallenge(nonce)));
        return list;
    }

    /** payload 路线的应答解读。 */
    enum LegacyAnswer {
        /** 尚未应答。 */
        PENDING,
        /** 未理解（Velocity 交来 {@code null}：客户端回了无负载应答）。路径路线客户端、原版客户端都是这样。 */
        NOT_UNDERSTOOD,
        /** 旧 Fabric 客户端报告已完成 {@code joinServer}。 */
        AUTHENTICATED,
        /** 旧 Fabric 客户端报告没有有效正版会话（或负载损坏）。 */
        UNAUTHENTICATED
    }

    /**
     * 解读 payload 路线的应答体。Velocity 的约定：客户端应答 {@code success=false}（无负载）时交来 {@code null}，
     * 否则交来负载字节。<b>路径路线的应答不解读</b>——它本来就是空的，理解与否无从区分，一律靠 {@code hasJoined}。
     */
    static LegacyAnswer interpretLegacyAnswer(byte[] body) {
        if (body == null) {
            return LegacyAnswer.NOT_UNDERSTOOD;
        }
        return PremiumAuthProtocol.decodeAnswer(body).authenticated()
            ? LegacyAnswer.AUTHENTICATED
            : LegacyAnswer.UNAUTHENTICATED;
    }

    /** 核验未通过时交给共享策略/日志的原因串（措辞与 mod 变体一致）。 */
    static String failureReason(String username, LegacyAnswer legacy, boolean answered) {
        if (!answered) {
            return "premium challenge timed out";
        }
        if (legacy == LegacyAnswer.UNAUTHENTICATED) {
            return "client reported no valid premium session";
        }
        return "session server did not confirm " + username;
    }

    // ---- 核验结局 ----

    /** 会话服核验（生产：{@link PremiumVerificationService#verify}）。 */
    @FunctionalInterface
    interface Verifier {
        VerifiedProfile verify(String username, String serverId, String clientIp) throws Exception;
    }

    /** 失败处置策略（生产：{@link PremiumVerificationService#rejectionMessage}；返回 {@code null}=宽松放行）。 */
    @FunctionalInterface
    interface RejectionPolicy {
        String rejectionMessage(String username, String reason);
    }

    /**
     * 一次验证的结局。
     *
     * @param kind    结局类别
     * @param profile 正版档案（仅 {@link Kind#VERIFIED}）
     * @param message 断开提示（仅 {@link Kind#REJECT}）
     * @param reason  未通过原因（非 {@link Kind#VERIFIED}）
     * @param error   核验过程中的异常（可空，供日志）
     */
    record Outcome(Kind kind, VerifiedProfile profile, String message, String reason, Throwable error) {
        enum Kind {
            /** 核验通过：换成正版档案。 */
            VERIFIED,
            /** 未通过、宽松放行：保留 Velocity 的离线档案。 */
            OFFLINE,
            /** 未通过、需拒绝（strict / 正版身份保护）。 */
            REJECT
        }
    }

    /**
     * 两条应答都到齐（或超时）后的核验与处置——strict / lenient / 正版身份保护三分支完全交给共享的
     * {@link RejectionPolicy}，与 mod 变体同一套语义与提示。
     * <ul>
     *   <li>已应答：调 {@code hasJoined}；通过 → {@link Outcome.Kind#VERIFIED}。</li>
     *   <li>未通过 / 核验异常 / 超时（超时不再调 {@code hasJoined}，同 mod 变体门控超时）：
     *       策略返回提示 → {@link Outcome.Kind#REJECT}，否则 {@link Outcome.Kind#OFFLINE}。</li>
     * </ul>
     */
    static Outcome resolve(
        String username,
        String serverId,
        String clientIp,
        LegacyAnswer legacy,
        boolean answered,
        Verifier verifier,
        RejectionPolicy policy
    ) {
        Throwable error = null;
        if (answered) {
            try {
                VerifiedProfile profile = verifier.verify(username, serverId, clientIp);
                if (profile != null) {
                    return new Outcome(Outcome.Kind.VERIFIED, profile, null, null, null);
                }
            } catch (Throwable t) {
                error = t;
            }
        }
        String reason = error != null ? "verification error: " + error : failureReason(username, legacy, answered);
        String message = policy.rejectionMessage(username, reason);
        return message == null
            ? new Outcome(Outcome.Kind.OFFLINE, null, null, reason, error)
            : new Outcome(Outcome.Kind.REJECT, null, message, reason, error);
    }

    // ---- 档案映射 ----

    /**
     * 正版档案 → Velocity {@link GameProfile}：UUID/名字/皮肤等 properties 取会话服返回值；
     * 原离线档案上会话服没给的同名之外的 property（如 Velocity 为 1.7–1.12 Forge 客户端追加的
     * {@code forgeClient} 令牌）原样保留。Velocity 的 {@code Property} 不接受 {@code null} 签名，
     * 无签名时写空串（Velocity 编码时把空签名写作「无签名」）。
     */
    static GameProfile toVelocityProfile(VerifiedProfile verified, GameProfile original) {
        List<GameProfile.Property> properties = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (MojangPremiumVerifier.Property property : verified.properties()) {
            if (property.name() == null || property.value() == null) {
                continue;
            }
            properties.add(new GameProfile.Property(property.name(), property.value(),
                property.signature() == null ? "" : property.signature()));
            names.add(property.name());
        }
        if (original != null) {
            for (GameProfile.Property property : original.getProperties()) {
                if (!names.contains(property.getName())) {
                    properties.add(property);
                }
            }
        }
        return new GameProfile(verified.id(), verified.name(), properties);
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
