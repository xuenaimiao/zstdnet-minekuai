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
