package cn.tohsaka.factory.zstdnet.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumAuthStateTest {

    @Test
    void autoFollowsOnlineMode() {
        assertTrue(PremiumAuthState.resolveEnabled("auto", true));
        assertFalse(PremiumAuthState.resolveEnabled("auto", false));
    }

    @Test
    void blankOrUnknownTreatedAsAuto() {
        assertTrue(PremiumAuthState.resolveEnabled("", true));
        assertFalse(PremiumAuthState.resolveEnabled(null, false));
        assertTrue(PremiumAuthState.resolveEnabled("weird", true));
    }

    @Test
    void onForcesEnabledRegardlessOfOnlineMode() {
        assertTrue(PremiumAuthState.resolveEnabled("on", false));
        assertTrue(PremiumAuthState.resolveEnabled("true", false));
    }

    @Test
    void offForcesDisabledRegardlessOfOnlineMode() {
        assertFalse(PremiumAuthState.resolveEnabled("off", true));
        assertFalse(PremiumAuthState.resolveEnabled("false", true));
    }

    @Test
    void strictModeResolution() {
        assertTrue(PremiumAuthState.resolveStrict("strict"));
        assertFalse(PremiumAuthState.resolveStrict("lenient"));
        assertFalse(PremiumAuthState.resolveStrict(null));
        assertFalse(PremiumAuthState.resolveStrict(""));
    }

    @Test
    void blankConfigWithoutInjectorFallsBackToMojang() {
        assertEquals(
            Collections.singletonList(MojangPremiumVerifier.MOJANG_SESSION_BASE),
            PremiumAuthState.parseSessionBases("", null));
        assertEquals(
            Collections.singletonList(MojangPremiumVerifier.MOJANG_SESSION_BASE),
            PremiumAuthState.parseSessionBases(null, null));
    }

    @Test
    void blankConfigWithDetectedInjectorPrefersSkinStation() {
        List<String> bases = PremiumAuthState.parseSessionBases("", "https://littleskin.cn/api/yggdrasil/");
        assertEquals(Arrays.asList(
            "https://littleskin.cn/api/yggdrasil",
            MojangPremiumVerifier.MOJANG_SESSION_BASE), bases);
    }

    @Test
    void explicitListIsRespectedVerbatimAndDeduplicated() {
        // 显式配置时完全按管理员填写的列表来，不追加自动探测结果。
        List<String> bases = PremiumAuthState.parseSessionBases(
            "https://skin.example.com/api/yggdrasil/, mojang,MOJANG ; https://skin.example.com/api/yggdrasil",
            "https://other.example.com/api/yggdrasil");
        assertEquals(Arrays.asList(
            "https://skin.example.com/api/yggdrasil",
            MojangPremiumVerifier.MOJANG_SESSION_BASE), bases);
    }

    @Test
    void emptyEntriesTreatedAsMojang() {
        assertEquals(
            Collections.singletonList(MojangPremiumVerifier.MOJANG_SESSION_BASE),
            PremiumAuthState.parseSessionBases(" , ; ", null));
    }
}
