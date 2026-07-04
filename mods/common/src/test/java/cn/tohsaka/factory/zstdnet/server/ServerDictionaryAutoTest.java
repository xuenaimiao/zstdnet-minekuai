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

package cn.tohsaka.factory.zstdnet.server;

import cn.tohsaka.factory.zstdnet.core.compress.DictionaryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖 {@code dictionary_auto} 的「生效字典名」解析：显式 dictionary= 优先、auto 在训练产物出现后自动回退到 trained.dict。
 * 这是「一键全自动」的关键——运行时压缩与 play 阶段自动下发都依赖同一个解析结果，必须一致。
 */
class ServerDictionaryAutoTest {

    @Test
    void explicitDictionaryAlwaysWins(@TempDir Path configDir) {
        Properties props = new Properties();
        props.setProperty("dictionary", "my.dict");
        props.setProperty("dictionary_auto", "true");
        assertEquals("my.dict", ServerProxyConfigFile.resolveDictionaryName(props, configDir));
    }

    @Test
    void autoOffResolvesToNothing(@TempDir Path configDir) {
        Properties props = new Properties();
        props.setProperty("dictionary", "");
        props.setProperty("dictionary_auto", "false");
        assertEquals("", ServerProxyConfigFile.resolveDictionaryName(props, configDir));
    }

    @Test
    void autoBeforeTrainingResolvesToNothing(@TempDir Path configDir) {
        Properties props = new Properties();
        props.setProperty("dictionary_auto", "true");
        // 还没训练出 trained.dict —— 解析为空，服务端按无字典运行（学习中）。
        assertEquals("", ServerProxyConfigFile.resolveDictionaryName(props, configDir));
    }

    @Test
    void autoAfterTrainingResolvesToTrainedDict(@TempDir Path configDir) throws Exception {
        Properties props = new Properties();
        props.setProperty("dictionary_auto", "true");

        Path trained = DictionaryFiles.dictDir(configDir).resolve(ServerProxyConfigFile.AUTO_TRAINED_DICT);
        Files.createDirectories(trained.getParent());
        Files.write(trained, new byte[]{1, 2, 3});

        // 训练产物出现后，auto 自动回退到 trained.dict（无需管理员改任何配置）。
        assertEquals(ServerProxyConfigFile.AUTO_TRAINED_DICT, ServerProxyConfigFile.resolveDictionaryName(props, configDir));
    }
}
