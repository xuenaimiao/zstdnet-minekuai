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

package cn.tohsaka.factory.zstdnet.core.cache;

import cn.tohsaka.factory.zstdnet.platform.Platforms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 客户端<b>跨会话</b>区块缓存的磁盘持久层（每个目标服务器一份，按 {@code host_port} 隔离）。整发的区块帧
 * （{@link ChunkCacheFormat#BLOCK_FULL}）以 {@link Hash128} 为键落盘；重连同一服务器时载入内存形成 warm 集合，
 * 客户端在握手后把这些 hash 作为 {@link ChunkManifest} 发给服务端，服务端据此对它们发
 * {@link ChunkCacheFormat#BLOCK_WARM_REF}（8/16 字节令牌替代整块），跨会话省下重传——这是相对 zstd LDM
 * （会话内、窗口内）真正不重叠的收益。
 *
 * <p><b>完整性即正确性：</b>WARM_REF 的字节由本地磁盘重放、两端都无法再比对，故加载时<b>重算 content128 校验</b>，
 * 与文件名不符（位翻转 / 半截写入 / 被篡改）的条目<b>直接丢弃</b>——不进 warm、不进 manifest、不会被 WARM_REF →
 * 绝不以错字节服务。写入用「临时文件 + 原子改名」避免半截文件。配合 128 位键的抗碰撞，WARM_REF 安全。
 *
 * <p>预算 {@code maxBytes}（磁盘与内存 warm 共用一个值，与会话内 16 MiB LRU 独立）超出时按最久未写淘汰、并删文件。
 * 线程安全（同一服务器的多条连接可共享同一实例）。
 */
public final class ChunkCacheStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnet-chunk-cache");
    private static final String SUFFIX = ".chunk";
    private static final int ENTRY_OVERHEAD = 64;

    /** 进程内按目录复用：同一 MC 会话内多次连同一服务器只读盘一次。 */
    private static final ConcurrentHashMap<String, ChunkCacheStore> OPEN = new ConcurrentHashMap<>();

    private final Path dir;
    private final long maxBytes;
    // 插入顺序 = 淘汰顺序（最久未写在前）。值为帧字节，键为其 content128。
    private final LinkedHashMap<Hash128, byte[]> entries = new LinkedHashMap<>();
    private long curBytes = 0L;

    private ChunkCacheStore(Path dir, long maxBytes) {
        this.dir = dir;
        this.maxBytes = Math.max(1L, maxBytes);
        load();
    }

    /** 打开（或复用）某目标服务器的持久缓存。 */
    public static ChunkCacheStore open(String host, int port, long maxBytes) {
        Path base = Platforms.get().configDir().resolve("zstdnet-cache").resolve(keyDir(host, port));
        return OPEN.computeIfAbsent(base.toAbsolutePath().toString(), k -> new ChunkCacheStore(base, maxBytes));
    }

    /** 单测钩子：在显式目录上打开（不走进程缓存、不依赖 Platform）。 */
    static ChunkCacheStore openAt(Path dir, long maxBytes) {
        return new ChunkCacheStore(dir, maxBytes);
    }

    /**
     * 本会话的 warm 快照（hash128 → 帧字节）：返回当前已加载条目的<b>不可变副本</b>（仅复制引用）。会话全程读它服务
     * WARM_REF，故即便 {@link #put} 期间淘汰了某条目，引用仍在、服务不失败。其键集即客户端要发的 manifest。
     */
    public synchronized Map<Hash128, byte[]> snapshotWarm() {
        return Map.copyOf(entries);
    }

    /**
     * 写入一个整发帧（key 须为 {@code content128(frame)}）。已存在则忽略（幂等）。写盘用临时文件 + 原子改名；
     * 随后按预算淘汰最久未写条目（删文件）。任一磁盘异常只记日志、不抛——持久化是尽力而为，失败不影响连接。
     */
    public synchronized void put(Hash128 key, byte[] frame) {
        if (entries.containsKey(key)) {
            return;
        }
        if (!writeFile(key, frame)) {
            return; // 落盘失败：不计入内存集合，保持磁盘/内存一致
        }
        entries.put(key, frame);
        curBytes += (long) frame.length + ENTRY_OVERHEAD;
        evictToBudget();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long bytes() {
        return curBytes;
    }

    // ---- 内部 ----

    private void load() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).forEach(files::add);
        } catch (IOException e) {
            LOGGER.warn("[chunk_cache] cannot list persist dir {}: {}", dir, e.toString());
            return;
        }
        // 按最后修改时间升序：最久未写在前，越新越靠后（与淘汰顺序一致）。
        files.sort(Comparator.comparingLong(ChunkCacheStore::lastModified));
        int loaded = 0;
        int dropped = 0;
        for (Path p : files) {
            String name = p.getFileName().toString();
            Hash128 key = Hash128.fromHex(name.substring(0, name.length() - SUFFIX.length()));
            if (key == null) {
                dropped++;
                deleteQuietly(p);
                continue;
            }
            byte[] data;
            try {
                data = Files.readAllBytes(p);
            } catch (IOException e) {
                dropped++;
                continue;
            }
            // 完整性校验：内容须哈希回文件名键，否则视为损坏，丢弃（绝不以错字节服务 WARM_REF）。
            if (data.length == 0 || data.length > ChunkCacheFormat.MAX_FRAME_LENGTH
                || !Hashing.content128(data).equals(key)) {
                dropped++;
                deleteQuietly(p);
                continue;
            }
            entries.put(key, data);
            curBytes += (long) data.length + ENTRY_OVERHEAD;
            loaded++;
            if (curBytes > maxBytes) {
                evictToBudget();
            }
        }
        if (loaded > 0 || dropped > 0) {
            LOGGER.info("[chunk_cache] persist loaded {} entries ({} KiB) from {}{}",
                loaded, curBytes / 1024, dir, dropped > 0 ? " (dropped " + dropped + " corrupt)" : "");
        }
    }

    private boolean writeFile(Hash128 key, byte[] frame) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(key.toHex() + SUFFIX);
            Path tmp = dir.resolve(key.toHex() + SUFFIX + ".tmp");
            Files.write(tmp, frame);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.debug("[chunk_cache] persist write failed {}: {}", key, e.toString());
            return false;
        }
    }

    private void evictToBudget() {
        var it = entries.entrySet().iterator();
        while (curBytes > maxBytes && entries.size() > 1 && it.hasNext()) {
            Map.Entry<Hash128, byte[]> victim = it.next();
            it.remove();
            curBytes -= (long) victim.getValue().length + ENTRY_OVERHEAD;
            deleteQuietly(dir.resolve(victim.getKey().toHex() + SUFFIX));
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    /** 把 {@code host:port} 规整成安全的目录名（仅保留字母数字/点/横线，其余→下划线）。 */
    private static String keyDir(String host, int port) {
        String h = host == null ? "unknown" : host.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(h.length() + 8);
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-') ? c : '_');
        }
        sb.append('_').append(port);
        return sb.toString();
    }
}
