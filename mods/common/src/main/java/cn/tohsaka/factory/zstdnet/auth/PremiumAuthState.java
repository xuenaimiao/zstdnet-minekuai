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

import java.util.Locale;

/**
 * 「登录阶段正版验证」的运行时已解析状态（单一真源，无 MC 类型）。
 * <p>
 * 专用服启动时由 {@code DedicatedServerAutoPort} 解析 {@code premium_verification}（auto/on/off）+
 * {@code server.properties online-mode} 后 {@link #configure} 写入；各变体的登录挂钩据 {@link #isEnabled()}
 * 决定是否发起验证、据 {@link #isStrict()} 决定验证不通过时放行还是断开。
 */
public final class PremiumAuthState {

    private static volatile boolean enabled;
    private static volatile boolean strict;
    private static volatile String sessionBaseUrl = MojangPremiumVerifier.MOJANG_SESSION_BASE;
    private static volatile boolean passRealIp;

    private PremiumAuthState() {
    }

    /**
     * 解析 {@code premium_verification} 三态开关：{@code auto} 跟随后端 {@code online-mode}；
     * {@code on}/{@code off} 手动强制。无法识别的值按 {@code auto} 处理。
     */
    public static boolean resolveEnabled(String premiumVerification, boolean onlineMode) {
        String mode = premiumVerification == null ? "" : premiumVerification.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "on", "true", "force" -> true;
            case "off", "false", "disable", "disabled" -> false;
            default -> onlineMode; // auto（含空/未知值）
        };
    }

    /** 由 {@code premium_verification_mode} 解析是否严格（拒绝未通过验证者）。 */
    public static boolean resolveStrict(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return "strict".equals(value);
    }

    /** 写入已解析状态（启动期调用）。 */
    public static void configure(boolean enabledValue, boolean strictValue, String sessionBase, boolean passRealIpValue) {
        enabled = enabledValue;
        strict = strictValue;
        if (sessionBase != null && !sessionBase.isBlank()) {
            sessionBaseUrl = sessionBase.trim();
        }
        passRealIp = passRealIpValue;
    }

    /** 关闭并复位（非专用服 / 配置禁用时）。 */
    public static void disable() {
        enabled = false;
        strict = false;
        passRealIp = false;
        sessionBaseUrl = MojangPremiumVerifier.MOJANG_SESSION_BASE;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isStrict() {
        return strict;
    }

    public static String sessionBaseUrl() {
        return sessionBaseUrl;
    }

    public static boolean passRealIp() {
        return passRealIp;
    }
}
