package cn.tohsaka.factory.zstdnet.core.limit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashNameGuardTest {

    @Test
    void shortVisitBlocksOtherIpsUntilBanExpires() {
        FlashNameGuard guard = new FlashNameGuard();
        guard.onJoin("Krean", "1.1.1.1", 1_000L);
        guard.onLeave("Krean", "1.1.1.1", 2_000L, 12_000L, 180_000L);
        assertTrue(guard.isBlocked("Krean", "2.2.2.2", 3_000L));
        assertTrue(guard.isBlocked("krean", "8.8.8.8", 3_000L));
        assertFalse(guard.isBlocked("Krean", "2.2.2.2", 183_000L));
    }

    @Test
    void trustedIpCanReturnAfterAnotherIpFlashes() {
        FlashNameGuard guard = new FlashNameGuard();
        guard.onJoin("Meng_Mei_", "9.9.9.9", 1_000L);
        guard.onLeave("Meng_Mei_", "9.9.9.9", 20_000L, 12_000L, 180_000L);
        guard.onJoin("Meng_Mei_", "1.1.1.1", 30_000L);
        guard.onLeave("Meng_Mei_", "1.1.1.1", 31_000L, 12_000L, 180_000L);
        assertTrue(guard.isBlocked("Meng_Mei_", "1.1.1.1", 32_000L));
        assertFalse(guard.isBlocked("Meng_Mei_", "9.9.9.9", 32_000L));
    }

    @Test
    void disabledFlashDoesNotBlock() {
        FlashNameGuard guard = new FlashNameGuard();
        guard.onJoin("djj", "1.1.1.1", 1_000L);
        guard.onLeave("djj", "1.1.1.1", 1_500L, 0L, 180_000L);
        assertFalse(guard.isBlocked("djj", "2.2.2.2", 2_000L));
    }
}
