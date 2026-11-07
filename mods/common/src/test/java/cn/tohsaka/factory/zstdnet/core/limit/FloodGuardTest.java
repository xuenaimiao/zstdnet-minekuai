package cn.tohsaka.factory.zstdnet.core.limit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodGuardTest {

    @Test
    void perIpConnLimit() {
        FloodGuard guard = new FloodGuard(settings(2, 0, 0, 0, 0));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.PER_IP_CONN, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("2.2.2.2"));
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
    }

    @Test
    void perIpRateBansThenExpires() {
        AtomicLong now = new AtomicLong(1_000_000L);
        FloodGuard guard = new FloodGuard(
            FloodGuard.Settings.of(0, 2, Duration.ofSeconds(10), Duration.ofSeconds(30), 0, 0, 0, Duration.ZERO),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("8.8.8.8"));
        guard.end("8.8.8.8");
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("8.8.8.8"));
        guard.end("8.8.8.8");
        assertEquals(FloodGuard.DenyReason.PER_IP_RATE, guard.begin("8.8.8.8"));
        assertEquals(FloodGuard.DenyReason.BANNED, guard.begin("8.8.8.8"));
        now.addAndGet(31_000L);
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("8.8.8.8"));
    }

    @Test
    void globalConnLimitIndependentOfIp() {
        FloodGuard guard = new FloodGuard(settings(0, 0, 3, 0, 0));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.0.0.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.0.0.2"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.0.0.3"));
        assertEquals(FloodGuard.DenyReason.GLOBAL_CONN, guard.begin("1.0.0.4"));
        assertEquals(3, guard.globalActiveConn());
        guard.end("1.0.0.1");
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.0.0.4"));
        assertEquals(3, guard.globalActiveConn());
    }

    @Test
    void globalRateAdmitsTcpAndEntersLockdown() {
        AtomicLong now = new AtomicLong(5_000L);
        FloodGuard guard = new FloodGuard(
            FloodGuard.Settings.of(0, 0, Duration.ofSeconds(10), Duration.ofSeconds(1), 0, 2, 0, Duration.ofSeconds(20))
                .withLockdownLoginInterval(Duration.ZERO),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("10.0.0.1"));
        guard.end("10.0.0.1");
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("10.0.0.2"));
        guard.end("10.0.0.2");
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("10.0.0.3"));
        assertTrue(guard.inLockdown());
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("10.0.0.3"));
        now.addAndGet(21_000L);
        assertFalse(guard.inLockdown());
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("10.0.0.3"));
    }

    @Test
    void lockdownSecondTryAdmittedWhileStrangersStayOut() {
        AtomicLong now = new AtomicLong(9_000L);
        FloodGuard guard = new FloodGuard(
            FloodGuard.Settings.of(0, 0, Duration.ofSeconds(10), Duration.ZERO, 10, 0, 1, Duration.ofSeconds(20))
                .withLockdownLoginInterval(Duration.ofSeconds(2)),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("2.2.2.2"));
        assertEquals(FloodGuard.DenyReason.LOGIN_RATE, guard.beginLogin("2.2.2.2"));
        assertTrue(guard.inLockdown());

        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("3.3.3.3"));
        now.addAndGet(2_000L);
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("3.3.3.3"));
        guard.releaseLoginAdmission("3.3.3.3");
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("3.3.3.3"));

        now.addAndGet(5_000L);
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("4.4.4.4"));
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("5.5.5.5"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("2.2.2.2"));
    }

    @Test
    void lockdownSameIpRetryAdmittedOnce() {
        AtomicLong now = new AtomicLong(9_000L);
        FloodGuard guard = new FloodGuard(
            FloodGuard.Settings.of(0, 0, Duration.ofSeconds(10), Duration.ZERO, 20, 0, 1, Duration.ofSeconds(30))
                .withLockdownLoginInterval(Duration.ZERO),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("2.2.2.2"));
        assertEquals(FloodGuard.DenyReason.LOGIN_RATE, guard.beginLogin("2.2.2.2"));
        assertTrue(guard.inLockdown());

        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("3.3.3.3"));
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("3.3.3.3"));

        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("4.4.4.4"));
        assertEquals(FloodGuard.DenyReason.LOCKDOWN, guard.beginLogin("4.4.4.4"));
    }

    @Test
    void repeatedBansEscalateThenStayPermanent() {
        AtomicLong now = new AtomicLong(1_000_000L);
        FloodGuard guard = new FloodGuard(
            FloodGuard.Settings.of(0, 1, Duration.ofSeconds(10), Duration.ofSeconds(1), 0, 0, 0, Duration.ZERO)
                .withBanPermanentAfter(4),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.PER_IP_RATE, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.BANNED, guard.begin("1.1.1.1"));
        now.addAndGet(11_000L);
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.PER_IP_RATE, guard.begin("1.1.1.1"));
        now.addAndGet(4_000L);
        assertEquals(FloodGuard.DenyReason.BANNED, guard.begin("1.1.1.1"));
        now.addAndGet(11_000L);
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.PER_IP_RATE, guard.begin("1.1.1.1"));
        now.addAndGet(21_000L);
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.PER_IP_RATE, guard.begin("1.1.1.1"));
        now.addAndGet(86_400_000L);
        assertEquals(FloodGuard.DenyReason.BANNED, guard.begin("1.1.1.1"));
    }

    @Test
    void shortPlaySessionBansRepeatingIp() {
        AtomicLong now = new AtomicLong(1_000_000L);
        FloodGuard guard = new FloodGuard(
            settings(0, 0, 0, 0, 0).withFlashSession(Duration.ofSeconds(12)),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.notePlaySession("1.1.1.1", 2_000L);
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.BANNED, guard.begin("1.1.1.1"));
        now.addAndGet(31_000L);
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
    }

    @Test
    void longPlaySessionAndDisabledFlashDoNotBan() {
        FloodGuard guard = new FloodGuard(settings(0, 0, 0, 0, 0).withFlashSession(Duration.ofSeconds(12)));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.notePlaySession("1.1.1.1", 60_000L);
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));

        FloodGuard legacy = new FloodGuard(settings(0, 0, 0, 0, 0));
        assertEquals(FloodGuard.DenyReason.NONE, legacy.begin("2.2.2.2"));
        legacy.notePlaySession("2.2.2.2", 500L);
        legacy.end("2.2.2.2");
        assertEquals(FloodGuard.DenyReason.NONE, legacy.begin("2.2.2.2"));
    }

    @Test
    void bypassIpSkipsRateLimits() {
        FloodGuard guard = new FloodGuard(
            settings(1, 1, 1, 1, 1).withBypassIps(Collections.singleton("9.9.9.9"))
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("1.1.1.1"));
        guard.end("1.1.1.1");
        assertEquals(FloodGuard.DenyReason.PER_IP_RATE, guard.begin("1.1.1.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("9.9.9.9"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("9.9.9.9"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("9.9.9.9"));
    }

    @Test
    void loginRateLockdownDoesNotConsumeConnectionSlotTwice() {
        AtomicLong now = new AtomicLong(9_000L);
        FloodGuard guard = new FloodGuard(
            FloodGuard.Settings.of(0, 0, Duration.ofSeconds(10), Duration.ZERO, 10, 0, 1, Duration.ofSeconds(15)),
            now::get
        );
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("9.9.9.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("9.9.9.1"));
        assertEquals(FloodGuard.DenyReason.NONE, guard.begin("9.9.9.2"));
        assertEquals(FloodGuard.DenyReason.LOGIN_RATE, guard.beginLogin("9.9.9.2"));
        assertTrue(guard.inLockdown());
        assertEquals(2, guard.globalActiveConn());
        guard.end("9.9.9.2");
        assertEquals(1, guard.globalActiveConn());
    }

    @Test
    void zeroLimitsPreserveLegacyUnlimited() {
        FloodGuard guard = new FloodGuard(settings(0, 0, 0, 0, 0));
        for (int i = 0; i < 50; i++) {
            assertEquals(FloodGuard.DenyReason.NONE, guard.begin("5.5.5." + i));
            assertEquals(FloodGuard.DenyReason.NONE, guard.beginLogin("5.5.5." + i));
        }
        assertEquals(50, guard.globalActiveConn());
        assertFalse(guard.inLockdown());
    }

    private static FloodGuard.Settings settings(
        int maxConnPerIp,
        int maxReqPerWindow,
        int maxGlobalConn,
        int maxGlobalReq,
        int maxLogin
    ) {
        return FloodGuard.Settings.of(
            maxConnPerIp,
            maxReqPerWindow,
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            maxGlobalConn,
            maxGlobalReq,
            maxLogin,
            Duration.ofSeconds(20)
        );
    }
}
