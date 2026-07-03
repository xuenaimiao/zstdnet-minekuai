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

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * 检测服务端 JVM 是否以 <a href="https://github.com/yushijinhun/authlib-injector">authlib-injector</a>
 * 启动（{@code -javaagent:authlib-injector.jar=<皮肤站 API 根地址>}），并提取皮肤站 API 根地址。
 * <p>
 * 用途：管理员给后端挂了 authlib-injector（LittleSkin / LinkSkin 等皮肤站）却没在
 * {@code zstdnet-server.properties} 里配置 {@code premium_session_server} 时，内置正版验证若只查
 * Mojang 官方会话服，皮肤站玩家将永远核验不过（表现为 strict 被拒 / lenient 静默降级离线身份）。
 * 自动把探测到的皮肤站纳入核验基址列表可实现零配置兼容（见 {@link PremiumAuthState#configure}）。
 * <p>
 * 探测方式与 authlib-injector 版本无关：只解析 JVM 启动参数，不触碰其任何类。
 */
public final class AuthlibInjectorDetector {

    private AuthlibInjectorDetector() {
    }

    /** 从当前 JVM 启动参数探测 authlib-injector 的皮肤站 API 根地址；未挂 agent 或解析失败返回 {@code null}。 */
    public static String detectApiRoot() {
        try {
            return extractApiRoot(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable t) {
            // 受限环境可能拿不到 RuntimeMXBean（如被 SecurityManager/模块化裁剪拦下）：静默视作未检测到。
            return null;
        }
    }

    /**
     * 从 JVM 参数列表提取 {@code -javaagent:<...authlib-injector...>.jar=<url>} 的 {@code <url>}
     * （去掉末尾斜杠）；没有匹配项返回 {@code null}。纯函数，便于单测。
     */
    static String extractApiRoot(List<String> jvmArgs) {
        if (jvmArgs == null) {
            return null;
        }
        for (String arg : jvmArgs) {
            if (arg == null) {
                continue;
            }
            String trimmed = arg.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith("-javaagent:")) {
                continue;
            }
            String value = trimmed.substring("-javaagent:".length());
            int eq = value.indexOf('=');
            if (eq <= 0 || eq >= value.length() - 1) {
                continue; // agent 无参数 → 不是带皮肤站地址的 authlib-injector 用法
            }
            String jarName = value.substring(0, eq).replace('\\', '/');
            int slash = jarName.lastIndexOf('/');
            if (slash >= 0) {
                jarName = jarName.substring(slash + 1);
            }
            if (!jarName.toLowerCase(Locale.ROOT).contains("authlib-injector")) {
                continue;
            }
            String url = value.substring(eq + 1).trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.isEmpty()) {
                return url;
            }
        }
        return null;
    }
}
