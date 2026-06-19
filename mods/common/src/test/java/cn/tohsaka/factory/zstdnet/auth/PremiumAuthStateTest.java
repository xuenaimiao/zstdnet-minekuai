package cn.tohsaka.factory.zstdnet.auth;

import org.junit.jupiter.api.Test;

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
}
