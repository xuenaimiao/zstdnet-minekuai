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

import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.LegacyAnswer;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Route;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一条被接管的登录连接的验证会话（{@code PreLoginEvent} 建立，{@code GameProfileRequestEvent} 消费）。
 * <p>
 * 线程模型：应答回调由 Velocity 在该连接的 netty 事件循环上调用（{@code LoginInboundConnection#handleLoginPluginResponse}），
 * 而消费方在异步事件线程上——故全部状态用原子量/volatile，应答到齐以 {@link #answered()} 这个 future 通知。
 * 本类不做任何阻塞操作（事件循环上不可阻塞）。
 */
final class VelocityPremiumSession {

    private final String username;
    private final byte[] nonce;
    private final String clientIp;
    private final AtomicInteger outstanding;
    private final Set<Route> seen = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> answered = new CompletableFuture<>();
    private volatile LegacyAnswer legacyAnswer = LegacyAnswer.PENDING;

    VelocityPremiumSession(String username, byte[] nonce, String clientIp, int expectedAnswers) {
        this.username = username;
        this.nonce = nonce.clone();
        this.clientIp = clientIp;
        this.outstanding = new AtomicInteger(expectedAnswers);
        if (expectedAnswers <= 0) {
            this.answered.complete(null);
        }
    }

    String username() {
        return this.username;
    }

    /** 与客户端 {@code joinServer} 一致的 serverId（两条路线共用同一 nonce）。 */
    String serverId() {
        return PremiumAuthProtocol.serverIdFromNonce(this.nonce);
    }

    /** {@code premium_pass_real_ip} 时交给会话服的 IP（未开启为 {@code null}）。 */
    String clientIp() {
        return this.clientIp;
    }

    LegacyAnswer legacyAnswer() {
        return this.legacyAnswer;
    }

    /** 所有查询都已应答时完成。 */
    CompletableFuture<Void> answered() {
        return this.answered;
    }

    /**
     * 记录一条应答（Velocity 的 {@code MessageConsumer} 回调）。同一路线重复应答只计一次；
     * payload 路线顺带解读应答内容，路径路线的应答体无信息量、不解读。
     */
    void onAnswer(Route route, byte[] body) {
        if (!this.seen.add(route)) {
            return;
        }
        if (route == Route.LEGACY) {
            this.legacyAnswer = VelocityPremiumLogic.interpretLegacyAnswer(body);
        }
        if (this.outstanding.decrementAndGet() <= 0) {
            this.answered.complete(null);
        }
    }
}
