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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 「登录阶段正版验证」的运行时已解析状态（单一真源，无 MC 类型）。
 * <p>
 * 专用服启动时由 {@code DedicatedServerAutoPort} 解析 {@code premium_verification}（auto/on/off）+
 * {@code server.properties online-mode} 后 {@link #configure} 写入；各变体的登录挂钩据 {@link #isEnabled()}
 * 决定是否发起验证、据 {@link #isStrict()} 决定验证不通过时放行还是断开。
 * <p>
 * 会话服基址支持<b>多个</b>（逗号/分号分隔，按序核验，见 {@link #parseSessionBases}）：
 * Mojang 官方与 authlib-injector 皮肤站可并存；未显式配置且检测到服务端挂了 authlib-injector 时，
 * 自动把皮肤站纳入核验（皮肤站优先），实现零配置兼容外置登录。
 */
public final class PremiumAuthState {

    private static final List<String> DEFAULT_BASES =
        Collections.singletonList(MojangPremiumVerifier.MOJANG_SESSION_BASE);

    private static volatile boolean enabled;
    private static volatile boolean strict;
    private static volatile List<String> sessionBaseUrls = DEFAULT_BASES;
    private static volatile boolean passRealIp;
    private static volatile boolean uuidGuard;

    private PremiumAuthState() {
    }

    /**
     * 解析 {@code premium_verification} 三态开关：{@code auto} 跟随后端 {@code online-mode}；
     * {@code on}/{@code off} 手动强制。无法识别的值按 {@code auto} 处理。
     */
    public static boolean resolveEnabled(String premiumVerification, boolean onlineMode) {
        String mode = premiumVerification == null ? "" : premiumVerification.trim().toLowerCase(Locale.ROOT);
        boolean result;
        switch (mode) {
            case "on":
            case "true":
            case "force":
                result = true;
                break;
            case "off":
            case "false":
            case "disable":
            case "disabled":
                result = false;
                break;
            default:
                result = onlineMode; // auto（含空/未知值）
                break;
        }
        return result;
    }

    /** 由 {@code premium_verification_mode} 解析是否严格（拒绝未通过验证者）。 */
    public static boolean resolveStrict(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return "strict".equals(value);
    }

    /** 写入已解析状态（启动期调用）。会话服基址列表见 {@link #parseSessionBases}（含 authlib-injector 自动探测）。 */
    public static void configure(
        boolean enabledValue,
        boolean strictValue,
        String sessionBase,
        boolean passRealIpValue,
        boolean uuidGuardValue
    ) {
        enabled = enabledValue;
        strict = strictValue;
        sessionBaseUrls = parseSessionBases(sessionBase, AuthlibInjectorDetector.detectApiRoot());
        passRealIp = passRealIpValue;
        uuidGuard = uuidGuardValue;
    }

    /**
     * 解析 {@code premium_session_server} 为核验基址列表（纯函数，便于单测）。
     * <ul>
     *   <li>显式配置：逗号/分号分隔、按序保留；条目 {@code mojang}（或空）代表官方会话服；去尾部斜杠、去重。
     *       此时完全尊重管理员配置，不追加自动探测结果。</li>
     *   <li>留空（默认）：若探测到服务端以 authlib-injector 启动，用「皮肤站 → Mojang 官方」两级核验；
     *       否则仅 Mojang 官方。</li>
     * </ul>
     */
    static List<String> parseSessionBases(String raw, String detectedInjectorRoot) {
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split("[,;]")) {
                String entry = part.trim();
                if (entry.isEmpty() || "mojang".equalsIgnoreCase(entry)) {
                    bases.add(MojangPremiumVerifier.MOJANG_SESSION_BASE);
                } else {
                    bases.add(stripTrailingSlash(entry));
                }
            }
        }
        if (bases.isEmpty()) {
            if (detectedInjectorRoot != null && !detectedInjectorRoot.trim().isEmpty()) {
                bases.add(stripTrailingSlash(detectedInjectorRoot.trim()));
            }
            bases.add(MojangPremiumVerifier.MOJANG_SESSION_BASE);
        }
        return Collections.unmodifiableList(new ArrayList<>(bases));
    }

    private static String stripTrailingSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 关闭并复位（非专用服 / 配置禁用时）。 */
    public static void disable() {
        enabled = false;
        strict = false;
        passRealIp = false;
        uuidGuard = false;
        sessionBaseUrls = DEFAULT_BASES;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isStrict() {
        return strict;
    }

    /** 核验基址列表（不可变，至少含一项）。 */
    public static List<String> sessionBaseUrls() {
        return sessionBaseUrls;
    }

    public static boolean passRealIp() {
        return passRealIp;
    }

    /** 正版身份保护（{@code premium_uuid_guard}）是否开启。 */
    public static boolean uuidGuardEnabled() {
        return uuidGuard;
    }
}
