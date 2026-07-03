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

import cn.tohsaka.factory.zstdnet.platform.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * 「曾以正版身份进入本服」的玩家名单（正版身份保护 {@code premium_uuid_guard} 的数据源）。
 * <p>
 * 每次内置正版验证成功即登记 {@code 玩家名(小写) → 正版 UUID}，持久化在配置目录的
 * {@value #FILE_NAME}（与 {@code zstdnet-server.properties} 同目录）。此后同名玩家若验证
 * <b>不</b>通过（启动器会话过期、盗版客户端冒名等），登录挂钩据此名单拒绝并明确提示，
 * 而不是按 lenient 静默回落离线 UUID——那会加载不到原先绑定正版 UUID 的 playerdata，
 * 玩家侧表现为「背包/进度被清空」。
 * <p>
 * 名单是纯附加数据：删除文件或删除某一行即解除对应玩家的保护。每次查询都直接读文件，
 * 管理员手工编辑后无需重启（登录频率低，开销可忽略）。写入走临时文件 + 原子替换。
 */
public final class PremiumPlayerRegistry {

    public static final String FILE_NAME = "zstdnet-premium-players.properties";

    private static final Logger LOGGER = LoggerFactory.getLogger(PremiumPlayerRegistry.class);
    private static final Object LOCK = new Object();

    private PremiumPlayerRegistry() {
    }

    /** 验证成功后登记（或更新）玩家的正版 UUID。 */
    public static void recordVerified(String name, UUID id) {
        recordVerified(defaultPath(), name, id);
    }

    /** 查询该玩家名此前登记的正版 UUID；从未登记（或条目损坏）返回 {@code null}。 */
    public static UUID recordedUuid(String name) {
        return recordedUuid(defaultPath(), name);
    }

    private static Path defaultPath() {
        return Platforms.get().configDir().resolve(FILE_NAME);
    }

    static void recordVerified(Path file, String name, UUID id) {
        if (name == null || name.trim().isEmpty() || id == null) {
            return;
        }
        String key = normalize(name);
        synchronized (LOCK) {
            Properties props = load(file);
            if (id.toString().equals(props.getProperty(key))) {
                return; // 未变化，避免无谓磁盘写
            }
            props.setProperty(key, id.toString());
            store(file, props);
        }
    }

    static UUID recordedUuid(Path file, String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String value;
        synchronized (LOCK) {
            value = load(file).getProperty(normalize(name));
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static Properties load(Path file) {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed reading premium player registry {}: {}", file, e.toString());
        }
        return props;
    }

    private static void store(Path file, Properties props) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out,
                    " ZstdNet premium identity guard registry (auto-maintained).\n"
                        + " 记录曾以正版身份通过内置验证进入本服的玩家名 -> 正版 UUID。\n"
                        + " 当同名玩家的正版会话校验未通过时，据此拒绝其以离线身份进入（防背包/数据丢失，premium_uuid_guard）。\n"
                        + " 删除某行即解除对应玩家的保护；删除本文件即全部解除。");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed writing premium player registry {}: {}", file, e.toString());
        }
    }
}
