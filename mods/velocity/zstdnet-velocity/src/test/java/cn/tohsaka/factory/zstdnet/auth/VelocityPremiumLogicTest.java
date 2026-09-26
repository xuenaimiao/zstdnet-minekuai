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
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Challenge;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.LegacyAnswer;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Outcome;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.PreLoginAction;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Route;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.StartupMode;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityPremiumLogicTest {

    private static final byte[] NONCE = {
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff
    };
    private static final UUID PREMIUM_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    // ---- 启用判定 ----

    @Test
    void autoFollowsVelocityOnlineMode() {
        assertEquals(StartupMode.ENABLED, VelocityPremiumLogic.startupMode("auto", true));
        assertEquals(StartupMode.DISABLED, VelocityPremiumLogic.startupMode("auto", false));
        assertEquals(StartupMode.ENABLED, VelocityPremiumLogic.startupMode("", true));
        assertEquals(StartupMode.ENABLED, VelocityPremiumLogic.startupMode("whatever", true));
    }

    @Test
    void offWhileVelocityOnlineWarnsAboutEncryption() {
        assertEquals(StartupMode.DISABLED_ENCRYPTED, VelocityPremiumLogic.startupMode("off", true));
        assertEquals(StartupMode.DISABLED, VelocityPremiumLogic.startupMode("off", false));
    }

    @Test
    void onForcesVerificationEvenWhenVelocityOffline() {
        assertEquals(StartupMode.ENABLED, VelocityPremiumLogic.startupMode("on", false));
        assertEquals(StartupMode.ENABLED, VelocityPremiumLogic.startupMode("ON", true));
    }

    // ---- 会话服配置 ----

    @Test
    void explicitSessionServerConfigWins() {
        assertEquals("https://littleskin.cn/api/yggdrasil,mojang", VelocityPremiumLogic.effectiveSessionServerConfig(
            "  https://littleskin.cn/api/yggdrasil,mojang ", "https://other.example/session/minecraft/hasJoined"));
    }

    @Test
    void defaultOrMissingVelocitySessionPropertyLeavesAutoDetection() {
        assertEquals("", VelocityPremiumLogic.effectiveSessionServerConfig("", null));
        assertEquals("", VelocityPremiumLogic.effectiveSessionServerConfig(null,
            VelocityPremiumLogic.VELOCITY_DEFAULT_HAS_JOINED));
        assertEquals("", VelocityPremiumLogic.effectiveSessionServerConfig(" ", "not a url"));
    }

    @Test
    void customVelocitySessionPropertyBecomesSkinSitePlusMojang() {
        String config = VelocityPremiumLogic.effectiveSessionServerConfig("",
            "https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/hasJoined");
        assertEquals("https://littleskin.cn/api/yggdrasil/sessionserver,mojang", config);
        assertEquals(
            Arrays.asList("https://littleskin.cn/api/yggdrasil/sessionserver", MojangPremiumVerifier.MOJANG_SESSION_BASE),
            PremiumAuthState.parseSessionBases(config, null));
    }

    @Test
    void sessionBaseDerivationToleratesQueryAndSlashes() {
        assertEquals("https://skin.example/api", VelocityPremiumLogic.sessionBaseFromHasJoinedUrl(
            "https://skin.example/api/session/minecraft/hasJoined/?username=%s&serverId=%s"));
        assertNull(VelocityPremiumLogic.sessionBaseFromHasJoinedUrl("https://skin.example/api/whatever"));
        assertNull(VelocityPremiumLogic.sessionBaseFromHasJoinedUrl("/session/minecraft/hasJoined"));
    }

    // ---- 连接判定 ----

    @Test
    void registeredConnectionIsAlwaysZstdnet() throws Exception {
        InetAddress publicIp = InetAddress.getByName("203.0.113.9");
        assertTrue(VelocityPremiumLogic.isZstdnetConnection(true, true, publicIp, "play.example.com"));
        assertTrue(VelocityPremiumLogic.isZstdnetConnection(false, true, publicIp, null));
    }

    @Test
    void activeInjectorMeansUnregisteredIsVanilla() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertFalse(VelocityPremiumLogic.isZstdnetConnection(true, false, loopback, hostWithMarker("198.51.100.7")));
    }

    @Test
    void fallbackNeedsBothLoopbackAndMarker() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress loopback6 = InetAddress.getByName("::1");
        InetAddress publicIp = InetAddress.getByName("203.0.113.9");
        assertTrue(VelocityPremiumLogic.isZstdnetConnection(false, false, loopback, hostWithMarker("198.51.100.7")));
        assertTrue(VelocityPremiumLogic.isZstdnetConnection(false, false, loopback6, hostWithMarker("198.51.100.7")));
        // FRP 等经回环转发的原版玩家：只有回环、没有标记 → 不得误判（否则强制离线会丢正版身份）
        assertFalse(VelocityPremiumLogic.isZstdnetConnection(false, false, loopback, "play.example.com"));
        assertFalse(VelocityPremiumLogic.isZstdnetConnection(false, false, loopback, "play.example.com\0FML3\0"));
        // 非回环伪造标记：不信任
        assertFalse(VelocityPremiumLogic.isZstdnetConnection(false, false, publicIp, hostWithMarker("198.51.100.7")));
        assertFalse(VelocityPremiumLogic.isZstdnetConnection(false, false, null, hostWithMarker("198.51.100.7")));
    }

    @Test
    void realIpIsDecodedFromMarkerAmongLoaderMarkers() {
        String raw = "play.example.com\0FML3\0" + VelocityPremiumLogic.REAL_IP_MARKER + b64("2001:db8::1");
        assertEquals("2001:db8::1", VelocityPremiumLogic.realIpFromRawHost(raw));
        assertEquals("198.51.100.7", VelocityPremiumLogic.realIpFromRawHost(hostWithMarker("198.51.100.7")));
    }

    @Test
    void malformedMarkersYieldNoIp() {
        assertNull(VelocityPremiumLogic.realIpFromRawHost(null));
        assertNull(VelocityPremiumLogic.realIpFromRawHost("play.example.com"));
        assertNull(VelocityPremiumLogic.realIpFromRawHost("play.example.com\0zstdnet-real-ip="));
        assertNull(VelocityPremiumLogic.realIpFromRawHost("play.example.com\0zstdnet-real-ip=!!notbase64!!"));
        assertNull(VelocityPremiumLogic.realIpFromRawHost(hostWithMarker("evil.example.com")));
    }

    @Test
    void verificationIpPrefersRegisteredRealIp() {
        assertEquals("198.51.100.1", VelocityPremiumLogic.clientIpForVerification("198.51.100.1",
            hostWithMarker("198.51.100.7")));
        assertEquals("198.51.100.7", VelocityPremiumLogic.clientIpForVerification(null, hostWithMarker("198.51.100.7")));
        assertNull(VelocityPremiumLogic.clientIpForVerification(" ", "play.example.com"));
    }

    // ---- PreLogin 处置 ----

    @Test
    void interceptsOnlyUndecidedLoginsFromVerifiableProtocols() {
        assertEquals(PreLoginAction.INTERCEPT, VelocityPremiumLogic.preLoginAction(767, true, false, false, true));
        assertEquals(PreLoginAction.INTERCEPT,
            VelocityPremiumLogic.preLoginAction(VelocityPremiumLogic.MIN_PROTOCOL, true, false, false, true));
        assertEquals(PreLoginAction.SKIP_DENIED, VelocityPremiumLogic.preLoginAction(767, false, false, false, true));
        assertEquals(PreLoginAction.SKIP_NOT_LOGIN_PHASE,
            VelocityPremiumLogic.preLoginAction(767, true, false, false, false));
        assertEquals(PreLoginAction.SKIP_OTHER_PLUGIN_DECIDED,
            VelocityPremiumLogic.preLoginAction(767, true, true, false, true));
        assertEquals(PreLoginAction.SKIP_OTHER_PLUGIN_DECIDED,
            VelocityPremiumLogic.preLoginAction(767, true, false, true, true));
    }

    @Test
    void forge1165AndOlderAreLeftToVelocity() {
        // 1.16.5 = 754：该版 ZstdNet 从未实现正版验证钩子
        assertEquals(PreLoginAction.SKIP_PROTOCOL_UNSUPPORTED,
            VelocityPremiumLogic.preLoginAction(754, true, false, false, true));
        assertEquals(PreLoginAction.SKIP_PROTOCOL_UNSUPPORTED,
            VelocityPremiumLogic.preLoginAction(340, true, false, false, true));
    }

    // ---- 查询 ----

    @Test
    void sendsPathRouteThenLegacyRouteWithTheSameNonce() {
        List<Challenge> challenges = VelocityPremiumLogic.challenges(NONCE);
        assertEquals(2, challenges.size());
        assertEquals(Route.PATH, challenges.get(0).route());
        assertEquals(Route.LEGACY, challenges.get(1).route());

        String serverId = PremiumAuthProtocol.serverIdFromNonce(NONCE);
        Challenge path = challenges.get(0);
        assertEquals("zstdnet:auth/" + serverId, path.channelId());
        assertEquals(serverId, PremiumAuthProtocol.serverIdFromChannelPath(path.name()));
        assertEquals(0, path.content().length);

        Challenge legacy = challenges.get(1);
        // 旧 Fabric 客户端经 fabric-api 注册的正是 zstdnet:auth 这个精确信道
        assertEquals(PremiumAuthProtocol.CHANNEL_NAMESPACE + ":" + PremiumAuthProtocol.CHANNEL_PATH, legacy.channelId());
        byte[] decoded = PremiumAuthProtocol.decodeChallenge(legacy.content());
        assertArrayEquals(NONCE, decoded);
        assertEquals(serverId, PremiumAuthProtocol.serverIdFromNonce(decoded));
        // 路径路线客户端对 payload 路线的信道不认（serverIdFromChannelPath("auth") == null）→ 回「未理解」
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath(legacy.name()));
    }

    @Test
    void channelsPassVelocityIdentifierValidation() {
        for (Challenge challenge : VelocityPremiumLogic.challenges(NONCE)) {
            MinecraftChannelIdentifier id = MinecraftChannelIdentifier.create(challenge.namespace(), challenge.name());
            assertEquals(challenge.channelId(), id.getId());
        }
    }

    // ---- 应答解读 ----

    @Test
    void legacyAnswerInterpretation() {
        assertEquals(LegacyAnswer.NOT_UNDERSTOOD, VelocityPremiumLogic.interpretLegacyAnswer(null));
        assertEquals(LegacyAnswer.AUTHENTICATED,
            VelocityPremiumLogic.interpretLegacyAnswer(PremiumAuthProtocol.encodeAnswer(true, "mojang")));
        assertEquals(LegacyAnswer.UNAUTHENTICATED,
            VelocityPremiumLogic.interpretLegacyAnswer(PremiumAuthProtocol.encodeAnswer(false, "")));
        assertEquals(LegacyAnswer.UNAUTHENTICATED, VelocityPremiumLogic.interpretLegacyAnswer(new byte[0]));
        assertEquals(LegacyAnswer.UNAUTHENTICATED, VelocityPremiumLogic.interpretLegacyAnswer(new byte[] {9, 1, 0}));
    }

    @Test
    void failureReasonsMatchModVariantWording() {
        assertEquals("premium challenge timed out",
            VelocityPremiumLogic.failureReason("alice", LegacyAnswer.PENDING, false));
        assertEquals("client reported no valid premium session",
            VelocityPremiumLogic.failureReason("alice", LegacyAnswer.UNAUTHENTICATED, true));
        assertEquals("session server did not confirm alice",
            VelocityPremiumLogic.failureReason("alice", LegacyAnswer.NOT_UNDERSTOOD, true));
        assertEquals("session server did not confirm alice",
            VelocityPremiumLogic.failureReason("alice", LegacyAnswer.AUTHENTICATED, true));
    }

    // ---- 核验结局 ----

    @Test
    void verifiedProfileSkipsRejectionPolicy() {
        VerifiedProfile profile = new VerifiedProfile(PREMIUM_ID, "Alice", new ArrayList<>());
        List<String> seen = new ArrayList<>();
        Outcome outcome = VelocityPremiumLogic.resolve("alice", "sid", "198.51.100.7", LegacyAnswer.NOT_UNDERSTOOD, true,
            (name, serverId, ip) -> {
                seen.add(name + "|" + serverId + "|" + ip);
                return profile;
            },
            (name, reason) -> {
                throw new AssertionError("policy must not run on success");
            });
        assertEquals(Outcome.Kind.VERIFIED, outcome.kind());
        assertSame(profile, outcome.profile());
        assertEquals(List.of("alice|sid|198.51.100.7"), seen);
    }

    @Test
    void lenientFailureKeepsOfflineIdentity() {
        List<String> reasons = new ArrayList<>();
        Outcome outcome = VelocityPremiumLogic.resolve("alice", "sid", null, LegacyAnswer.NOT_UNDERSTOOD, true,
            (name, serverId, ip) -> null,
            (name, reason) -> {
                reasons.add(name + "|" + reason);
                return null;
            });
        assertEquals(Outcome.Kind.OFFLINE, outcome.kind());
        assertNull(outcome.profile());
        assertEquals(List.of("alice|session server did not confirm alice"), reasons);
    }

    @Test
    void policyMessageRejects() {
        Outcome outcome = VelocityPremiumLogic.resolve("alice", "sid", null, LegacyAnswer.UNAUTHENTICATED, true,
            (name, serverId, ip) -> null,
            (name, reason) -> "denied:" + reason);
        assertEquals(Outcome.Kind.REJECT, outcome.kind());
        assertEquals("denied:client reported no valid premium session", outcome.message());
        assertNull(outcome.profile());
    }

    @Test
    void verifierErrorGoesThroughPolicyWithErrorReason() {
        IllegalStateException boom = new IllegalStateException("boom");
        Outcome outcome = VelocityPremiumLogic.resolve("alice", "sid", null, LegacyAnswer.NOT_UNDERSTOOD, true,
            (name, serverId, ip) -> {
                throw boom;
            },
            (name, reason) -> reason.startsWith("verification error: ") ? null : "unexpected");
        assertEquals(Outcome.Kind.OFFLINE, outcome.kind());
        assertSame(boom, outcome.error());
    }

    @Test
    void timeoutDoesNotCallSessionServer() {
        AtomicInteger calls = new AtomicInteger();
        Outcome outcome = VelocityPremiumLogic.resolve("alice", "sid", null, LegacyAnswer.PENDING, false,
            (name, serverId, ip) -> {
                calls.incrementAndGet();
                return null;
            },
            (name, reason) -> reason);
        assertEquals(0, calls.get());
        assertEquals(Outcome.Kind.REJECT, outcome.kind());
        assertEquals("premium challenge timed out", outcome.message());
    }

    @Test
    void sharedStrictLenientGuardSemanticsFlowThroughUnchanged() {
        UUID recorded = PREMIUM_ID;
        // 与 PremiumVerificationService#rejectionMessage 同一决策：strict 恒拒 / 保护命中拒 / 其余宽松
        VelocityPremiumLogic.RejectionPolicy strict = policy(true, true, null);
        VelocityPremiumLogic.RejectionPolicy guardHit = policy(false, true, recorded);
        VelocityPremiumLogic.RejectionPolicy guardOff = policy(false, false, recorded);
        VelocityPremiumLogic.RejectionPolicy lenient = policy(false, true, null);

        assertEquals(Outcome.Kind.REJECT, resolveFailure(strict).kind());
        Outcome guarded = resolveFailure(guardHit);
        assertEquals(Outcome.Kind.REJECT, guarded.kind());
        assertEquals(PremiumVerificationService.guardMessage("alice"), guarded.message());
        assertEquals(Outcome.Kind.OFFLINE, resolveFailure(guardOff).kind());
        assertEquals(Outcome.Kind.OFFLINE, resolveFailure(lenient).kind());
    }

    // ---- 档案映射 ----

    @Test
    void verifiedProfileMapsToVelocityProfile() {
        VerifiedProfile verified = new VerifiedProfile(PREMIUM_ID, "Alice", List.of(
            new MojangPremiumVerifier.Property("textures", "dGV4", "c2ln"),
            new MojangPremiumVerifier.Property("extra", "v", null)));
        GameProfile original = new GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:alice".getBytes(StandardCharsets.UTF_8)),
            "alice", List.of(
                new GameProfile.Property("forgeClient", "true", ""),
                new GameProfile.Property("textures", "offline", "")));

        GameProfile mapped = VelocityPremiumLogic.toVelocityProfile(verified, original);
        assertEquals(PREMIUM_ID, mapped.getId());
        assertEquals("Alice", mapped.getName());
        assertEquals(3, mapped.getProperties().size());
        GameProfile.Property textures = mapped.getProperties().get(0);
        assertEquals("textures", textures.getName());
        assertEquals("dGV4", textures.getValue());
        assertEquals("c2ln", textures.getSignature());
        GameProfile.Property extra = mapped.getProperties().get(1);
        assertEquals("extra", extra.getName());
        assertEquals("", extra.getSignature());
        GameProfile.Property token = mapped.getProperties().get(2);
        assertEquals("forgeClient", token.getName());
    }

    @Test
    void mappingWithoutOriginalProfile() {
        VerifiedProfile verified = new VerifiedProfile(PREMIUM_ID, "Alice", List.of());
        GameProfile mapped = VelocityPremiumLogic.toVelocityProfile(verified, null);
        assertEquals(PREMIUM_ID, mapped.getId());
        assertTrue(mapped.getProperties().isEmpty());
    }

    // ---- helpers ----

    private static Outcome resolveFailure(VelocityPremiumLogic.RejectionPolicy policy) {
        return VelocityPremiumLogic.resolve("alice", "sid", null, LegacyAnswer.NOT_UNDERSTOOD, true,
            (name, serverId, ip) -> null, policy);
    }

    private static VelocityPremiumLogic.RejectionPolicy policy(boolean strict, boolean guard, UUID recorded) {
        return (name, reason) -> {
            switch (PremiumVerificationService.decide(strict, guard, recorded)) {
                case STRICT_REJECT:
                    return "strict";
                case GUARD_REJECT:
                    return PremiumVerificationService.guardMessage(name);
                case ALLOW_OFFLINE:
                default:
                    return null;
            }
        };
    }

    private static String hostWithMarker(String ip) {
        return "play.example.com\0" + VelocityPremiumLogic.REAL_IP_MARKER + b64(ip);
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
