package cn.tohsaka.factory.zstdnet.auth;

import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.SessionFetcher;
import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.VerifiedProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangPremiumVerifierTest {

    private static final String SAMPLE =
        "{\n"
        + "  \"id\": \"069a79f444e94726a5befca90e38aaf5\",\n"
        + "  \"name\": \"Notch\",\n"
        + "  \"properties\": [\n"
        + "    {\"name\": \"textures\", \"value\": \"ewogIC...\", \"signature\": \"abc123\"}\n"
        + "  ]\n"
        + "}\n";

    @Test
    void parsesValidHasJoinedBody() {
        VerifiedProfile profile = MojangPremiumVerifier.parse(SAMPLE);
        assertNotNull(profile);
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profile.id());
        assertEquals("Notch", profile.name());
        assertEquals(1, profile.properties().size());
        assertEquals("textures", profile.properties().get(0).name());
        assertEquals("abc123", profile.properties().get(0).signature());
    }

    @Test
    void parsesBodyWithoutProperties() {
        VerifiedProfile profile = MojangPremiumVerifier.parse(
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}");
        assertNotNull(profile);
        assertTrue(profile.properties().isEmpty());
    }

    @Test
    void undashedUuidParsing() {
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            MojangPremiumVerifier.parseUndashedUuid("069a79f444e94726a5befca90e38aaf5"));
        assertNull(MojangPremiumVerifier.parseUndashedUuid("not-a-uuid"));
        assertNull(MojangPremiumVerifier.parseUndashedUuid(null));
    }

    @Test
    void verifyReturnsProfileOn200() {
        AtomicReference<String> calledUrl = new AtomicReference<>();
        SessionFetcher fetcher = url -> {
            calledUrl.set(url);
            return new SessionFetcher.Response(200, SAMPLE);
        };
        VerifiedProfile profile = MojangPremiumVerifier.verify(
            MojangPremiumVerifier.MOJANG_SESSION_BASE, "Notch", "deadbeef", null, fetcher);
        assertNotNull(profile);
        assertEquals("Notch", profile.name());
        assertTrue(calledUrl.get().contains("/session/minecraft/hasJoined?username=Notch&serverId=deadbeef"));
    }

    @Test
    void verifyPassesClientIpWhenProvided() {
        AtomicReference<String> calledUrl = new AtomicReference<>();
        SessionFetcher fetcher = url -> {
            calledUrl.set(url);
            return new SessionFetcher.Response(200, SAMPLE);
        };
        MojangPremiumVerifier.verify(MojangPremiumVerifier.MOJANG_SESSION_BASE, "Notch", "deadbeef", "203.0.113.7", fetcher);
        assertTrue(calledUrl.get().contains("&ip=203.0.113.7"));
    }

    @Test
    void verifyReturnsNullOn204Empty() {
        SessionFetcher fetcher = url -> new SessionFetcher.Response(204, "");
        assertNull(MojangPremiumVerifier.verify(MojangPremiumVerifier.MOJANG_SESSION_BASE, "Notch", "deadbeef", null, fetcher));
    }

    @Test
    void verifyReturnsNullWhenFetcherThrows() {
        SessionFetcher fetcher = url -> {
            throw new RuntimeException("network down");
        };
        assertNull(MojangPremiumVerifier.verify(MojangPremiumVerifier.MOJANG_SESSION_BASE, "Notch", "deadbeef", null, fetcher));
    }

    @Test
    void verifyRejectsBlankInputs() {
        SessionFetcher fetcher = url -> new SessionFetcher.Response(200, SAMPLE);
        assertNull(MojangPremiumVerifier.verify(MojangPremiumVerifier.MOJANG_SESSION_BASE, "", "deadbeef", null, fetcher));
        assertNull(MojangPremiumVerifier.verify(MojangPremiumVerifier.MOJANG_SESSION_BASE, "Notch", "", null, fetcher));
    }

    @Test
    void verifyRetriesWithSessionserverPathOn404() {
        // authlib-injector 皮肤站惯例给 API 根地址：直连 404 → 自动补 /sessionserver 路径重试。
        List<String> calls = new ArrayList<>();
        SessionFetcher fetcher = url -> {
            calls.add(url);
            return url.contains("/sessionserver/session/minecraft/hasJoined")
                ? new SessionFetcher.Response(200, SAMPLE)
                : new SessionFetcher.Response(404, "");
        };
        VerifiedProfile profile = MojangPremiumVerifier.verify(
            "https://littleskin.cn/api/yggdrasil", "Notch", "deadbeef", null, fetcher);
        assertNotNull(profile);
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).startsWith("https://littleskin.cn/api/yggdrasil/session/minecraft/hasJoined"));
        assertTrue(calls.get(1).startsWith("https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/hasJoined"));
    }

    @Test
    void verifyDoesNotRetryWhenBaseAlreadyHasSessionserverPath() {
        List<String> calls = new ArrayList<>();
        SessionFetcher fetcher = url -> {
            calls.add(url);
            return new SessionFetcher.Response(404, "");
        };
        assertNull(MojangPremiumVerifier.verify(
            "https://littleskin.cn/api/yggdrasil/sessionserver", "Notch", "deadbeef", null, fetcher));
        assertEquals(1, calls.size());
    }

    @Test
    void verifyDoesNotRetryOnPlainMiss204() {
        List<String> calls = new ArrayList<>();
        SessionFetcher fetcher = url -> {
            calls.add(url);
            return new SessionFetcher.Response(204, "");
        };
        assertNull(MojangPremiumVerifier.verify(
            "https://littleskin.cn/api/yggdrasil", "Notch", "deadbeef", null, fetcher));
        assertEquals(1, calls.size());
    }
}
