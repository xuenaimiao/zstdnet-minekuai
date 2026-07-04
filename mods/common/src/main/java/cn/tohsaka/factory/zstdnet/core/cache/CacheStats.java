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

import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端区块引用缓存的运行计数（仅用于 HUD / 指令展示，非正确性必需）。由解码端
 * {@link CacheUntransformingInputStream} 在每解出一个 block 时更新；HUD 旁路读取。线程安全（一写一读）。
 *
 * <p>“省下的字节”取每个引用类 block 的 {@code 重建帧字节 − 该 block 的线上字节}：REF/WARM_REF 是几字节令牌换回
 * 整块、PATCH 是小增量换回整块，故该值 ≈ 这层在 ZSTD <b>之前</b>替客户端省下的下行原始字节。
 */
public final class CacheStats {
    private final AtomicLong refHits = new AtomicLong();
    private final AtomicLong warmHits = new AtomicLong();
    private final AtomicLong patchHits = new AtomicLong();
    private final AtomicLong fullBlocks = new AtomicLong();
    private final AtomicLong savedBytes = new AtomicLong();

    /**
     * 登记一个已解出的 block。
     *
     * @param blockType         {@link ChunkCacheFormat} 的 BLOCK_* 类型
     * @param wireBytes         该 block 在 CRC 流里占的字节数（令牌/增量的成本）
     * @param reconstructedBytes 还原出的原始 MC 帧字节数
     */
    public void recordDecoded(int blockType, long wireBytes, long reconstructedBytes) {
        switch (blockType) {
            case ChunkCacheFormat.BLOCK_REF:
                refHits.incrementAndGet();
                addSaved(reconstructedBytes - wireBytes);
                break;
            case ChunkCacheFormat.BLOCK_WARM_REF:
                warmHits.incrementAndGet();
                addSaved(reconstructedBytes - wireBytes);
                break;
            case ChunkCacheFormat.BLOCK_PATCH:
                patchHits.incrementAndGet();
                addSaved(reconstructedBytes - wireBytes);
                break;
            case ChunkCacheFormat.BLOCK_FULL:
                fullBlocks.incrementAndGet();
                break;
            default:
                // PASSTHROUGH 等不计入。
                break;
        }
    }

    private void addSaved(long delta) {
        if (delta > 0) {
            savedBytes.addAndGet(delta);
        }
    }

    public long refHits() {
        return refHits.get();
    }

    public long warmHits() {
        return warmHits.get();
    }

    public long patchHits() {
        return patchHits.get();
    }

    public long fullBlocks() {
        return fullBlocks.get();
    }

    public long savedBytes() {
        return savedBytes.get();
    }

    /** REF + WARM_REF + PATCH 命中总数（>0 即说明缓存正在产生收益）。 */
    public long totalHits() {
        return refHits.get() + warmHits.get() + patchHits.get();
    }
}
