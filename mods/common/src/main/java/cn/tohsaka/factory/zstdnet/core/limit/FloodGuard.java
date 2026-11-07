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

package cn.tohsaka.factory.zstdnet.core.limit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 入口防刷：
 * <ul>
 *   <li>单 IP 并发 / 窗口请求，超限临时封禁；</li>
 *   <li>全局并发 / 全局窗口请求，挡住分布式假人换 IP 轰炸；</li>
 *   <li>登录握手窗口，超限进入 lockdown。lockdown 不掐 TCP：同一 IP 的下一次连接直接放行，一次性换 IP 的假人进不去。</li>
 * </ul>
 * 所有 {@code max_*} 取 {@code <=0} 表示该项不限制，保持历史默认行为。
 */
public final class FloodGuard {
    public enum DenyReason {
        NONE,
        BANNED,
        PER_IP_CONN,
        PER_IP_RATE,
        GLOBAL_CONN,
        GLOBAL_RATE,
        LOCKDOWN,
        LOGIN_RATE
    }

    public static final class Settings {
        public final int maxConnPerIp;
        public final int maxReqPerWindow;
        public final long windowMs;
        public final long banDurationMs;
        public final int maxGlobalConn;
        public final int maxGlobalReqPerWindow;
        public final int maxLoginPerWindow;
        public final long lockdownMs;
        public final long lockdownLoginIntervalMs;
        public final long flashSessionMs;
        public final Set<String> bypassIps;
        public final int banPermanentAfter;

        public Settings(
            int maxConnPerIp,
            int maxReqPerWindow,
            long windowMs,
            long banDurationMs,
            int maxGlobalConn,
            int maxGlobalReqPerWindow,
            int maxLoginPerWindow,
            long lockdownMs
        ) {
            this(
                maxConnPerIp,
                maxReqPerWindow,
                windowMs,
                banDurationMs,
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                lockdownMs,
                2_000L,
                0L,
                Collections.<String>emptySet(),
                0
            );
        }

        public Settings(
            int maxConnPerIp,
            int maxReqPerWindow,
            long windowMs,
            long banDurationMs,
            int maxGlobalConn,
            int maxGlobalReqPerWindow,
            int maxLoginPerWindow,
            long lockdownMs,
            long lockdownLoginIntervalMs,
            long flashSessionMs,
            Set<String> bypassIps
        ) {
            this(
                maxConnPerIp,
                maxReqPerWindow,
                windowMs,
                banDurationMs,
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                lockdownMs,
                lockdownLoginIntervalMs,
                flashSessionMs,
                bypassIps,
                0
            );
        }

        public Settings(
            int maxConnPerIp,
            int maxReqPerWindow,
            long windowMs,
            long banDurationMs,
            int maxGlobalConn,
            int maxGlobalReqPerWindow,
            int maxLoginPerWindow,
            long lockdownMs,
            long lockdownLoginIntervalMs,
            long flashSessionMs,
            Set<String> bypassIps,
            int banPermanentAfter
        ) {
            this.maxConnPerIp = maxConnPerIp;
            this.maxReqPerWindow = maxReqPerWindow;
            this.windowMs = Math.max(0L, windowMs);
            this.banDurationMs = Math.max(0L, banDurationMs);
            this.maxGlobalConn = maxGlobalConn;
            this.maxGlobalReqPerWindow = maxGlobalReqPerWindow;
            this.maxLoginPerWindow = maxLoginPerWindow;
            this.lockdownMs = Math.max(0L, lockdownMs);
            this.lockdownLoginIntervalMs = Math.max(0L, lockdownLoginIntervalMs);
            this.flashSessionMs = Math.max(0L, flashSessionMs);
            this.bypassIps = canonicalizeIps(bypassIps);
            this.banPermanentAfter = Math.max(0, banPermanentAfter);
        }

        public static Settings of(
            int maxConnPerIp,
            int maxReqPerWindow,
            Duration window,
            Duration banDuration,
            int maxGlobalConn,
            int maxGlobalReqPerWindow,
            int maxLoginPerWindow,
            Duration lockdown
        ) {
            return new Settings(
                maxConnPerIp,
                maxReqPerWindow,
                millis(window),
                millis(banDuration),
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                millis(lockdown)
            );
        }

        public Settings withLockdownLoginInterval(Duration interval) {
            return new Settings(
                maxConnPerIp,
                maxReqPerWindow,
                windowMs,
                banDurationMs,
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                lockdownMs,
                millis(interval),
                flashSessionMs,
                bypassIps,
                banPermanentAfter
            );
        }

        public Settings withFlashSession(Duration flashSession) {
            return new Settings(
                maxConnPerIp,
                maxReqPerWindow,
                windowMs,
                banDurationMs,
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                lockdownMs,
                lockdownLoginIntervalMs,
                millis(flashSession),
                bypassIps,
                banPermanentAfter
            );
        }

        public Settings withBypassIps(Set<String> ips) {
            return new Settings(
                maxConnPerIp,
                maxReqPerWindow,
                windowMs,
                banDurationMs,
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                lockdownMs,
                lockdownLoginIntervalMs,
                flashSessionMs,
                ips,
                banPermanentAfter
            );
        }

        public Settings withBanPermanentAfter(int strikes) {
            return new Settings(
                maxConnPerIp,
                maxReqPerWindow,
                windowMs,
                banDurationMs,
                maxGlobalConn,
                maxGlobalReqPerWindow,
                maxLoginPerWindow,
                lockdownMs,
                lockdownLoginIntervalMs,
                flashSessionMs,
                bypassIps,
                strikes
            );
        }

        private static Set<String> canonicalizeIps(Set<String> ips) {
            if (ips == null || ips.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> out = new LinkedHashSet<>();
            for (String ip : ips) {
                if (ip == null) {
                    continue;
                }
                String trimmed = ip.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
            return out.isEmpty() ? Collections.<String>emptySet() : Collections.unmodifiableSet(out);
        }

        private static long millis(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return 0L;
            }
            return duration.toMillis();
        }
    }

    private final Settings settings;
    private final LongSupplier clock;
    private final Map<String, GuardEntry> state = new HashMap<>();
    private final Deque<Long> globalRequestsMs = new ArrayDeque<>();
    private final Deque<Long> loginRequestsMs = new ArrayDeque<>();
    private int globalActiveConn;
    private long lockdownUntilMs;

    public FloodGuard(Settings settings) {
        this(settings, System::currentTimeMillis);
    }

    public FloodGuard(Settings settings, LongSupplier clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 新 TCP 连接入场。成功后必须在连接结束时调用 {@link #end(String)}。
     */
    public synchronized DenyReason begin(String ip) {
        long now = clock.getAsLong();
        pruneAll(now);
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        GuardEntry entry = state.computeIfAbsent(ip, ignored -> new GuardEntry());
        if (isBypass(ip)) {
            entry.activeConn++;
            globalActiveConn++;
            return DenyReason.NONE;
        }

        if (entry.bannedUntilMs > now) {
            return DenyReason.BANNED;
        }
        if (settings.maxGlobalConn > 0 && globalActiveConn >= settings.maxGlobalConn) {
            return DenyReason.GLOBAL_CONN;
        }
        if (settings.maxConnPerIp > 0 && entry.activeConn >= settings.maxConnPerIp) {
            return DenyReason.PER_IP_CONN;
        }
        if (settings.maxReqPerWindow > 0 && settings.windowMs > 0) {
            entry.requestsMs.addLast(now);
            if (entry.requestsMs.size() > settings.maxReqPerWindow) {
                applyBan(entry, now);
                return DenyReason.PER_IP_RATE;
            }
        }
        if (settings.maxGlobalReqPerWindow > 0 && settings.windowMs > 0) {
            if (globalRequestsMs.size() >= settings.maxGlobalReqPerWindow) {
                enterLockdown(now);
                // 不在这里掐断 TCP：否则假人占满窗口后真玩家只会看到「连接中断」。
            } else {
                globalRequestsMs.addLast(now);
            }
        }

        entry.activeConn++;
        globalActiveConn++;
        return DenyReason.NONE;
    }

    /**
     * 判定为登录/进服（非 MOTD 状态 ping）后再调用。lockdown 只拦这一层。
     * 同一 IP 的第一次登录只记账并拒绝。下一次要等过 {@code lockdown_login_interval} 才放行，
     * 立刻连开的第二条不进世界。这一次放行占用着，直到 {@link #releaseLoginAdmission(String)}。
     */
    public synchronized DenyReason beginLogin(String ip) {
        long now = clock.getAsLong();
        pruneAll(now);
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        if (isBypass(ip)) {
            return DenyReason.NONE;
        }
        GuardEntry entry = state.computeIfAbsent(ip, ignored -> new GuardEntry());
        if (lockdownUntilMs > now) {
            return beginLoginDuringLockdown(entry, now);
        }
        if (settings.maxLoginPerWindow > 0 && settings.windowMs > 0) {
            if (loginRequestsMs.size() >= settings.maxLoginPerWindow) {
                enterLockdown(now);
                touchLoginCredit(entry, now);
                entry.loginAttempts++;
                if (entry.firstLoginAttemptMs <= 0L) {
                    entry.firstLoginAttemptMs = now;
                }
                return DenyReason.LOGIN_RATE;
            }
            loginRequestsMs.addLast(now);
        }
        return DenyReason.NONE;
    }

    private DenyReason beginLoginDuringLockdown(GuardEntry entry, long now) {
        touchLoginCredit(entry, now);
        if (entry.loginAdmitted > 0) {
            return DenyReason.LOCKDOWN;
        }
        if (entry.loginAttempts <= 0 || entry.firstLoginAttemptMs <= 0L) {
            entry.loginAttempts = 1;
            entry.firstLoginAttemptMs = now;
            return DenyReason.LOCKDOWN;
        }
        // 间隔内的第二次多半是脚本连开两条。真人看完提示再点，会落在间隔之后。
        long wait = settings.lockdownLoginIntervalMs;
        if (wait > 0L && now - entry.firstLoginAttemptMs < wait) {
            return DenyReason.LOCKDOWN;
        }
        entry.loginAdmitted = 1;
        loginRequestsMs.addLast(now);
        return DenyReason.NONE;
    }

    /**
     * 放行的那条连接结束时调用，空出名额。同一 IP 断开后可以再进，不会一直卡在「再进一次」。
     */
    public synchronized void releaseLoginAdmission(String ip) {
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        GuardEntry entry = state.get(ip);
        if (entry != null) {
            entry.loginAdmitted = 0;
        }
    }

    private void touchLoginCredit(GuardEntry entry, long now) {
        long hold = Math.max(settings.windowMs, 60_000L);
        entry.loginCreditUntilMs = now + hold;
    }

    private boolean isBypass(String ip) {
        return ip != null && settings.bypassIps.contains(ip);
    }

    /**
     * 已经把登录流转发到后端之后调用。会话短于 {@code flash_session} 时，按 {@code ban_duration} 封这个 IP，
     * 避免同一地址反复进世界再秒退，把「进入/离开」刷进游戏日志。{@code flash_session <= 0} 时不生效。
     */
    public synchronized void notePlaySession(String ip, long durationMs) {
        if (settings.flashSessionMs <= 0L || durationMs < 0L || durationMs >= settings.flashSessionMs) {
            return;
        }
        long now = clock.getAsLong();
        pruneAll(now);
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        if (isBypass(ip)) {
            return;
        }
        GuardEntry entry = state.computeIfAbsent(ip, ignored -> new GuardEntry());
        applyBan(entry, now);
    }

    private void applyBan(GuardEntry entry, long now) {
        if (settings.banDurationMs <= 0L) {
            return;
        }
        entry.banCount++;
        long duration = banLengthMs(entry.banCount);
        if (duration == Long.MAX_VALUE) {
            entry.bannedUntilMs = Long.MAX_VALUE;
            entry.strikeUntilMs = Long.MAX_VALUE;
            return;
        }
        entry.bannedUntilMs = Math.max(entry.bannedUntilMs, now + duration);
        long remember = Math.max(duration, 24L * 60L * 60L * 1000L);
        entry.strikeUntilMs = Math.max(entry.strikeUntilMs, now + remember);
    }

    private long banLengthMs(int count) {
        if (settings.banPermanentAfter <= 0) {
            return Math.max(1L, settings.banDurationMs);
        }
        if (count >= settings.banPermanentAfter) {
            return Long.MAX_VALUE;
        }
        long base = Math.max(1L, settings.banDurationMs);
        if (count <= 1) {
            return base;
        }
        if (count == 2) {
            return base * 5L;
        }
        if (count == 3) {
            return base * 20L;
        }
        return base * 60L;
    }

    public synchronized void end(String ip) {
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        GuardEntry entry = state.get(ip);
        if (entry != null && entry.activeConn > 0) {
            entry.activeConn--;
        }
        if (globalActiveConn > 0) {
            globalActiveConn--;
        }
        long now = clock.getAsLong();
        pruneAll(now);
        if (entry != null && isRemovable(entry, now)) {
            state.remove(ip);
        }
    }

    public synchronized boolean inLockdown() {
        return lockdownUntilMs > clock.getAsLong();
    }

    public synchronized int globalActiveConn() {
        return globalActiveConn;
    }

    public synchronized void sweepExpired() {
        pruneAll(clock.getAsLong());
    }

    private void enterLockdown(long now) {
        long duration = settings.lockdownMs > 0 ? settings.lockdownMs : settings.windowMs;
        if (duration <= 0L) {
            duration = 20_000L;
        }
        lockdownUntilMs = Math.max(lockdownUntilMs, now + duration);
    }

    private void pruneAll(long now) {
        pruneDeque(globalRequestsMs, now);
        pruneDeque(loginRequestsMs, now);
        if (lockdownUntilMs > 0L && lockdownUntilMs <= now) {
            lockdownUntilMs = 0L;
        }
        Iterator<Map.Entry<String, GuardEntry>> it = state.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, GuardEntry> item = it.next();
            GuardEntry entry = item.getValue();
            pruneDeque(entry.requestsMs, now);
            if (isRemovable(entry, now)) {
                it.remove();
            }
        }
    }

    private void pruneDeque(Deque<Long> timestamps, long now) {
        if (settings.windowMs <= 0L) {
            timestamps.clear();
            return;
        }
        long cutoff = now - settings.windowMs;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.removeFirst();
        }
    }

    private boolean isRemovable(GuardEntry entry, long now) {
        return entry.activeConn == 0
            && entry.requestsMs.isEmpty()
            && entry.bannedUntilMs <= now
            && entry.loginCreditUntilMs <= now
            && entry.strikeUntilMs <= now;
    }

    private static final class GuardEntry {
        private int activeConn;
        private long bannedUntilMs;
        private int banCount;
        private long strikeUntilMs;
        private int loginAttempts;
        private int loginAdmitted;
        private long firstLoginAttemptMs;
        private long loginCreditUntilMs;
        private final Deque<Long> requestsMs = new ArrayDeque<>();
    }
}
