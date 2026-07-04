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

package cn.tohsaka.factory.zstdnet.core.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 客户端「自动下发字典」的本地缓存与按服务器解析。
 * <p>
 * 服务端在 play 阶段把字典推给客户端后，字典字节存到 {@code config/zstdnet/dict/auto/<dictId>.dict}，
 * 并在 {@code servers.properties} 里记录「服务器地址 → dictId」。下次连接同一服务器时，
 * {@link #resolveFor} 在建连前就把这本字典塞进 {@link CompressionOptions}，实现零手动配置。
 * <p>
 * 安全：字典大小有 {@link #MAX_DICTIONARY_BYTES} 上限，且只接受声明 id 与内容 id 一致的字典，
 * 防止恶意服务器塞爆客户端磁盘或投递脏文件。
 */
public final class ClientDictionaryStore {
    /** 单本自动字典的大小上限（1 MiB）。 */
    public static final long MAX_DICTIONARY_BYTES = 1L << 20;

    private static final String MAP_FILE = "servers.properties";

    private ClientDictionaryStore() {
    }

    private static Path autoDir(Path configDir) {
        return DictionaryFiles.dictDir(configDir).resolve("auto");
    }

    private static Path dictFile(Path configDir, long dictId) {
        return autoDir(configDir).resolve(Long.toUnsignedString(dictId) + ".dict");
    }

    /**
     * 建连时解析该服务器应使用的压缩参数：手动配置的字典优先，否则查自动缓存。
     * LDM 等其它参数沿用 {@code base}。
     */
    public static CompressionOptions resolveFor(Path configDir, String serverAddress, CompressionOptions base) {
        CompressionOptions effectiveBase = base == null ? CompressionOptions.none() : base;
        if (effectiveBase.hasDictionary()) {
            return effectiveBase; // 手动字典优先，不被自动缓存覆盖
        }
        long dictId = mappedDictId(configDir, serverAddress);
        if (dictId == 0L) {
            return effectiveBase;
        }
        byte[] dict = loadById(configDir, dictId);
        if (dict == null) {
            return effectiveBase;
        }
        return CompressionOptions.of(effectiveBase.longDistanceMatching(), effectiveBase.effectiveWindowLog(), dict);
    }

    /** 客户端是否已缓存该 id 的字典（用于决定是否向服务器请求下发）。 */
    public static boolean hasDictionary(Path configDir, long dictId) {
        return loadById(configDir, dictId) != null;
    }

    public static byte[] loadById(Path configDir, long dictId) {
        if (dictId == 0L) {
            return null;
        }
        Path file = dictFile(configDir, dictId);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length > 0 && ZstdCodecs.getDictIdFromDict(bytes) == dictId ? bytes : null;
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * 保存自动下发的字典，并记录「服务器地址 → dictId」映射。
     *
     * @return 是否成功新保存（失败/校验不过返回 false，调用方据此决定是否提示重连）
     */
    public static boolean store(Path configDir, String serverAddress, long dictId, byte[] dictBytes) throws IOException {
        if (dictId == 0L
            || dictBytes == null
            || dictBytes.length == 0
            || dictBytes.length > MAX_DICTIONARY_BYTES
            || ZstdCodecs.getDictIdFromDict(dictBytes) != dictId) {
            return false;
        }
        Path dir = autoDir(configDir);
        Files.createDirectories(dir);
        Files.write(dictFile(configDir, dictId), dictBytes);
        putMapping(configDir, serverAddress, dictId);
        return true;
    }

    private static long mappedDictId(Path configDir, String serverAddress) {
        if (serverAddress == null || serverAddress.trim().isEmpty()) {
            return 0L;
        }
        String value = loadMap(configDir).getProperty(normalize(serverAddress));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static void putMapping(Path configDir, String serverAddress, long dictId) throws IOException {
        if (serverAddress == null || serverAddress.trim().isEmpty()) {
            return;
        }
        Path dir = autoDir(configDir);
        Files.createDirectories(dir);
        Properties props = loadMap(configDir);
        props.setProperty(normalize(serverAddress), Long.toUnsignedString(dictId));
        try (OutputStream out = Files.newOutputStream(dir.resolve(MAP_FILE))) {
            props.store(out, "zstdnet auto dictionary mapping: server address -> dictionary id");
        }
    }

    private static Properties loadMap(Path configDir) {
        Properties props = new Properties();
        Path file = autoDir(configDir).resolve(MAP_FILE);
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
        return props;
    }

    private static String normalize(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
