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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从 {@link DictionarySampler} 采集的语料离线训练 ZSTD 字典。
 */
public final class DictionaryTrainer {
    /** 训练样本缓冲上限（喂给 ZstdDictTrainer 的总容量）。 */
    private static final int SAMPLE_BUFFER_BYTES = 64 * 1024 * 1024;
    /** 训练所需的最少样本数。ZDICT/COVER 原生下限约为 12（更少会报 “nb of samples too low”）。 */
    private static final int MIN_SAMPLES = 12;
    /** 训练所需的最少语料总量；过少训不出有意义的字典。ZDICT 会按语料自动收缩目标字典大小。 */
    private static final int MIN_CORPUS_BYTES = 128 * 1024;

    private DictionaryTrainer() {
    }

    /**
     * 训练字典。
     *
     * @param samplesDir   样本目录（{@code *.bin}）
     * @param dictSizeBytes 目标字典大小（典型 64KB~112KB）
     * @return 训练好的字典字节；样本不足或失败时返回 null
     */
    public static byte[] train(Path samplesDir, int dictSizeBytes) throws IOException {
        if (samplesDir == null || !Files.isDirectory(samplesDir)) {
            return null;
        }
        List<Path> sampleFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(samplesDir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".bin"))
                .forEach(sampleFiles::add);
        }
        if (sampleFiles.size() < MIN_SAMPLES) {
            return null;
        }

        Object trainer = ZstdCodecs.newDictTrainer(SAMPLE_BUFFER_BYTES, dictSizeBytes);
        long accepted = 0;
        for (Path file : sampleFiles) {
            byte[] sample = Files.readAllBytes(file);
            if (sample.length > 0 && ZstdCodecs.dictTrainerAddSample(trainer, sample)) {
                accepted += sample.length;
            }
        }
        // 语料过少则训不出有意义的字典（ZDICT 会按语料自动收缩字典大小，故用绝对下限而非按字典倍数）。
        if (accepted < MIN_CORPUS_BYTES) {
            return null;
        }
        try {
            byte[] dictionary = ZstdCodecs.dictTrainerTrain(trainer);
            return dictionary != null && dictionary.length > 0 ? dictionary : null;
        } catch (RuntimeException ex) {
            // ZstdException 等：样本不适合训练时抛出，按失败处理。
            return null;
        }
    }
}
