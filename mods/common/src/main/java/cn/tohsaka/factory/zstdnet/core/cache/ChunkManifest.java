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

import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 跨会话 manifest（{@code ZNCM} 帧）的编解码。客户端在握手后、login-start 前，于 c2s 流插入一个本帧，
 * 列出它磁盘缓存里持有的 {@link Hash128} 集合；服务端据此对这些区块发 {@link ChunkCacheFormat#BLOCK_WARM_REF}。
 *
 * <p>帧载荷布局（外层再由 {@code PacketIo} 套一层 VarInt 长度前缀）：
 * <pre>
 *   MAGIC(4='ZNCM')  VERSION(1)  COUNT(varint)  COUNT × HASH128(16 大端)
 * </pre>
 *
 * <p><b>服务端自纠错：</b>{@link #parse} 在魔数不匹配时返回 {@code null}（表示“这其实不是 manifest”，
 * 调用方应把该帧当普通包转发后端）——使“客户端没发 manifest / 版本错配”也不破坏流。魔数匹配但结构损坏 /
 * 超上限 → 抛 {@link IOException}（fail-closed）。
 */
public final class ChunkManifest {

    private ChunkManifest() {
    }

    /** 把 hash 集合编成 {@code ZNCM} 帧载荷（不含外层 PacketIo 长度前缀）。超过上限的多余条目被截断。 */
    public static byte[] encode(Collection<Hash128> hashes) {
        int count = Math.min(hashes.size(), ChunkCacheFormat.MAX_MANIFEST_ENTRIES);
        ByteArrayOutputStream out = new ByteArrayOutputStream(
            ChunkCacheFormat.MANIFEST_MAGIC.length + 1 + 5 + count * ChunkCacheFormat.HASH128_BYTES);
        out.write(ChunkCacheFormat.MANIFEST_MAGIC, 0, ChunkCacheFormat.MANIFEST_MAGIC.length);
        out.write(ChunkCacheFormat.MANIFEST_VERSION);
        byte[] countBytes = VarIntCodec.encode(count);
        out.write(countBytes, 0, countBytes.length);
        byte[] tmp = new byte[ChunkCacheFormat.HASH128_BYTES];
        int written = 0;
        for (Hash128 h : hashes) {
            if (written >= count) {
                break;
            }
            h.writeBytes(tmp, 0);
            out.write(tmp, 0, tmp.length);
            written++;
        }
        return out.toByteArray();
    }

    /**
     * 解析 {@code ZNCM} 帧载荷。
     *
     * @return hash 列表；若魔数不匹配返回 {@code null}（调用方据此判定“非 manifest，按普通包转发”）
     * @throws IOException 魔数匹配但版本不识别 / 计数越界 / 长度不符（fail-closed）
     */
    public static List<Hash128> parse(byte[] payload) throws IOException {
        if (payload == null || payload.length < ChunkCacheFormat.MANIFEST_MAGIC.length + 1) {
            return null;
        }
        for (int i = 0; i < ChunkCacheFormat.MANIFEST_MAGIC.length; i++) {
            if (payload[i] != ChunkCacheFormat.MANIFEST_MAGIC[i]) {
                return null; // 不是 manifest（自纠错：调用方转发为普通包）
            }
        }
        int pos = ChunkCacheFormat.MANIFEST_MAGIC.length;
        int version = payload[pos++] & 0xFF;
        if (version != ChunkCacheFormat.MANIFEST_VERSION) {
            throw new IOException("zstdnet chunk-cache: unsupported manifest version " + version);
        }
        VarIntRead countRead = VarIntCodec.read(payload, pos, payload.length);
        if (countRead == null) {
            throw new IOException("zstdnet chunk-cache: malformed manifest count");
        }
        int count = countRead.value();
        if (count < 0 || count > ChunkCacheFormat.MAX_MANIFEST_ENTRIES) {
            throw new IOException("zstdnet chunk-cache: manifest count out of range: " + count);
        }
        pos = countRead.next();
        long need = (long) count * ChunkCacheFormat.HASH128_BYTES;
        if (pos + need != payload.length) {
            throw new IOException("zstdnet chunk-cache: manifest length mismatch (count=" + count + ")");
        }
        List<Hash128> hashes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            hashes.add(Hash128.fromBytes(payload, pos));
            pos += ChunkCacheFormat.HASH128_BYTES;
        }
        return hashes;
    }
}
