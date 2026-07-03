/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.auth;

import cn.tohsaka.factory.zstdnet.auth.MojangPremiumVerifier.VerifiedProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 「登录阶段正版验证」的服务端编排门面：各变体登录挂钩（coremod {@code PremiumAuthServerHooks} /
 * Fabric {@code PremiumAuthSync}）统一走这里，保持核验与失败处置策略只有一份实现。
 * <ul>
 *   <li>{@link #verify}：按 {@link PremiumAuthState#sessionBaseUrls()} 逐个会话服核验（Mojang 官方 /
 *       authlib-injector 皮肤站可并存，serverId 一次性 nonce 保证只有玩家真正 join 过的那家会返回 200，
 *       多问几家不会误判），命中后登记进 {@link PremiumPlayerRegistry}（正版身份保护名单）。</li>
 *   <li>{@link #rejectionMessage}：核验失败时的处置决策——strict 一律拒绝；lenient 下若该玩家名
 *       此前曾以正版身份进服且 {@code premium_uuid_guard} 开启，同样拒绝并明确提示（防止静默回落
 *       离线 UUID 导致背包/数据丢失），其余照旧宽松放行。</li>
 * </ul>
 */
public final class PremiumVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PremiumVerificationService.class);

    private static final String STRICT_MESSAGE =
        "ZstdNet：正版验证失败，本服仅允许正版账号进入。\n"
            + "Premium verification failed — this server only accepts verified premium accounts.";

    private PremiumVerificationService() {
    }

    /**
     * 按配置的会话服基址列表逐一核验；命中即返回正版档案并登记身份保护名单，全部未命中返回 {@code null}。
     */
    public static VerifiedProfile verify(String username, String serverId, String clientIp) {
        List<String> bases = PremiumAuthState.sessionBaseUrls();
        for (String base : bases) {
            VerifiedProfile profile = MojangPremiumVerifier.verify(
                base, username, serverId, clientIp, MojangPremiumVerifier.defaultFetcher());
            if (profile != null) {
                if (bases.size() > 1) {
                    LOGGER.info("[zstdnet-server] premium session of {} confirmed by {}", profile.name(), base);
                }
                PremiumPlayerRegistry.recordVerified(profile.name(), profile.id());
                return profile;
            }
        }
        return null;
    }

    /**
     * 核验失败时的处置：返回 {@code null} 表示宽松放行（离线身份照常进服）；
     * 返回非空字符串表示应断开连接并把该消息展示给玩家。日志在本方法内统一打印。
     */
    public static String rejectionMessage(String username, String reason) {
        UUID recorded = (username == null || username.trim().isEmpty())
            ? null
            : PremiumPlayerRegistry.recordedUuid(username);
        Decision decision = decide(PremiumAuthState.isStrict(), PremiumAuthState.uuidGuardEnabled(), recorded);
        switch (decision) {
            case STRICT_REJECT:
                LOGGER.info("[zstdnet-server] rejected login (strict premium): {}", reason);
                return STRICT_MESSAGE;
            case GUARD_REJECT:
                LOGGER.info(
                    "[zstdnet-server] rejected login (premium identity guard): {} previously verified as {} but this session failed verification ({}). Remove the entry from {} to lift the guard.",
                    username, recorded, reason, PremiumPlayerRegistry.FILE_NAME);
                return guardMessage(username);
            case ALLOW_OFFLINE:
            default:
                LOGGER.info("[zstdnet-server] premium verification not satisfied ({}); proceeding with offline identity (lenient).", reason);
                return null;
        }
    }

    /** 纯决策函数（便于单测）：strict 恒拒绝；否则仅当保护开启且该名有正版记录时拒绝。 */
    static Decision decide(boolean strict, boolean uuidGuard, UUID recordedPremiumUuid) {
        if (strict) {
            return Decision.STRICT_REJECT;
        }
        if (uuidGuard && recordedPremiumUuid != null) {
            return Decision.GUARD_REJECT;
        }
        return Decision.ALLOW_OFFLINE;
    }

    static String guardMessage(String username) {
        return "ZstdNet：正版会话校验未通过，而玩家名 " + username + " 此前曾以正版身份进入本服。\n"
            + "为防止以离线身份进入后加载不到正版存档（背包/数据看似丢失），已阻止本次登录。\n"
            + "请在启动器重新登录（刷新正版/皮肤站会话）后重试；如确需离线进入，请联系管理员删除\n"
            + "config/" + PremiumPlayerRegistry.FILE_NAME + " 中的对应条目。\n"
            + "Premium session check failed, but \"" + username + "\" previously joined as a verified premium account.\n"
            + "Login blocked to protect your inventory/player data — please re-login in your launcher and retry.";
    }

    enum Decision {
        STRICT_REJECT,
        GUARD_REJECT,
        ALLOW_OFFLINE
    }
}
