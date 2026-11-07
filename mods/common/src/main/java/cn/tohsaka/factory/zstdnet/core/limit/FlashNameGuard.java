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

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 短连接进世界又秒退时，按角色名拦住下一次进服。
 * 曾经正常待够 {@code flash_session} 的来源地址仍然可以进。
 */
public final class FlashNameGuard {
    private static final int MAX_TRUSTED_IPS = 8;

    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Long> blockedUntilMs = new HashMap<>();
    private final Map<String, Set<String>> trustedIps = new HashMap<>();

    public synchronized void onJoin(String name, String ip, long now) {
        String key = key(name);
        if (key.isEmpty()) {
            return;
        }
        sessions.put(key, new Session(normalizeIp(ip), now));
    }

    public synchronized void onLeave(String name, String ip, long now, long flashMs, long banMs) {
        String key = key(name);
        if (key.isEmpty()) {
            return;
        }
        Session session = sessions.remove(key);
        if (session == null || flashMs <= 0L) {
            return;
        }
        long duration = Math.max(0L, now - session.joinedAtMs);
        String seenIp = session.ip.isEmpty() ? normalizeIp(ip) : session.ip;
        if (duration >= flashMs) {
            rememberTrusted(key, seenIp);
            return;
        }
        if (banMs <= 0L) {
            return;
        }
        long until = now + banMs;
        blockedUntilMs.merge(key, until, Math::max);
    }

    public synchronized boolean isBlocked(String name, String ip, long now) {
        String key = key(name);
        if (key.isEmpty()) {
            return false;
        }
        Long until = blockedUntilMs.get(key);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            blockedUntilMs.remove(key);
            return false;
        }
        Set<String> trusted = trustedIps.get(key);
        return trusted == null || !trusted.contains(normalizeIp(ip));
    }

    private void rememberTrusted(String key, String ip) {
        if (ip.isEmpty()) {
            return;
        }
        Set<String> trusted = trustedIps.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        trusted.add(ip);
        if (trusted.size() <= MAX_TRUSTED_IPS) {
            return;
        }
        Iterator<String> it = trusted.iterator();
        it.next();
        it.remove();
    }

    private static String key(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeIp(String ip) {
        if (ip == null) {
            return "";
        }
        return ip.trim();
    }

    private static final class Session {
        private final String ip;
        private final long joinedAtMs;

        private Session(String ip, long joinedAtMs) {
            this.ip = ip;
            this.joinedAtMs = joinedAtMs;
        }
    }
}
