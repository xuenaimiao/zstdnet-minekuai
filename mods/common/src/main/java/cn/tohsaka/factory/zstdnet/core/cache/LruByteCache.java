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

package cn.tohsaka.factory.zstdnet.core.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * 内容寻址的 LRU 字节缓存（hash → 帧字节），带硬字节预算。CRC 编码端（服务端）与解码端（客户端）各持一份，
 * <b>用同一 {@code maxBytes} + 同一套操作序列</b>，因此淘汰严格同步——这是“会话内 REF 不可能 miss”的基础：
 * 服务端只在 {@link #peek} 命中且整字节相等时发 REF；两端在 FULL 时 {@link #put}、在 REF 时 {@link #get}，
 * 操作顺序经可靠有序 TCP 完全一致 → LRU 顺序一致 → 同进同出。
 *
 * <p>三个操作语义须严格区分（否则两端 LRU 失步）：
 * <ul>
 *   <li>{@link #put}（FULL）：插入/更新 + 置为最近使用 + 按预算淘汰最久未用。两端都做。</li>
 *   <li>{@link #get}（REF）：命中则置为最近使用并返回；未命中返回 null。两端都做。</li>
 *   <li>{@link #peek}（仅服务端 REF 决策）：返回内容但<b>不改动 LRU 顺序</b>。客户端永不做。</li>
 * </ul>
 *
 * <p>非线程安全：每条连接一份、仅由该连接单线程使用。
 */
final class LruByteCache {
    private static final class Node {
        final long key;
        byte[] value;
        Node prev;
        Node next;

        Node(long key, byte[] value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * 每条目除载荷外的近似固定开销（key + 链表指针 + map 桶），计入预算更接近真实内存。
     * <p><b>锁步契约：</b>此值连同 {@link ChunkCacheFormat#DEFAULT_CACHE_BYTES} 决定逐出点，编/解码两端必须逐位一致。
     * 改动二者任一<b>必须同时抬升 {@link ChunkCacheFormat#MAX_SUPPORTED_VERSION}</b>（否则混合 release 的两端逐出点
     * 不同 → 会话内 REF/PATCH 基线 miss → fail-closed 反复断连）。
     */
    private static final int ENTRY_OVERHEAD = 64;

    private final Map<Long, Node> map = new HashMap<>();
    private final long maxBytes;
    private long curBytes = 0L;

    // 双向链表：head 为最近使用，tail 为最久未用（淘汰端）。
    private Node head;
    private Node tail;

    LruByteCache(long maxBytes) {
        this.maxBytes = Math.max(1L, maxBytes);
    }

    /** 命中并置为最近使用；未命中返回 null。 */
    byte[] get(long key) {
        Node node = map.get(key);
        if (node == null) {
            return null;
        }
        moveToHead(node);
        return node.value;
    }

    /** 仅查看内容，不改动 LRU 顺序（服务端 REF 决策用）。 */
    byte[] peek(long key) {
        Node node = map.get(key);
        return node == null ? null : node.value;
    }

    /** 插入/更新并置为最近使用，随后按预算淘汰最久未用条目。 */
    void put(long key, byte[] value) {
        Node node = map.get(key);
        if (node != null) {
            curBytes += (long) value.length - node.value.length;
            node.value = value;
            moveToHead(node);
        } else {
            node = new Node(key, value);
            map.put(key, node);
            addToHead(node);
            curBytes += (long) value.length + ENTRY_OVERHEAD;
        }
        evictToBudget();
    }

    int size() {
        return map.size();
    }

    long bytes() {
        return curBytes;
    }

    private void evictToBudget() {
        while (curBytes > maxBytes && map.size() > 1) {
            Node victim = tail;
            if (victim == null) {
                break;
            }
            remove(victim);
            map.remove(victim.key);
            curBytes -= (long) victim.value.length + ENTRY_OVERHEAD;
        }
    }

    private void addToHead(Node node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void remove(Node node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    private void moveToHead(Node node) {
        if (node == head) {
            return;
        }
        remove(node);
        addToHead(node);
    }
}
