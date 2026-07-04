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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ZSTD 训练字典在磁盘上的标准布局与读取。
 * <p>
 * 字典统一放在 Minecraft {@code config/} 下的 {@code zstdnet/dict/} 目录；服务端与客户端都从这里读取，
 * 服务端管理员只需把同一个 {@code .dict} 文件发给开启字典的玩家即可。
 */
public final class DictionaryFiles {
    private DictionaryFiles() {
    }

    /** 字典目录：{@code <configDir>/zstdnet/dict}。 */
    public static Path dictDir(Path configDir) {
        return configDir.resolve("zstdnet").resolve("dict");
    }

    /** 采样目录：{@code <configDir>/zstdnet/dict/samples}（训练字典用的原始语料）。 */
    public static Path samplesDir(Path configDir) {
        return dictDir(configDir).resolve("samples");
    }

    /** 把配置里的字典名解析成实际路径：绝对路径原样使用，否则相对 {@link #dictDir(Path)}。 */
    public static Path resolve(Path configDir, String nameOrPath) {
        Path candidate = Paths.get(nameOrPath.trim());
        return candidate.isAbsolute() ? candidate : dictDir(configDir).resolve(nameOrPath.trim());
    }

    /**
     * 读取字典字节。
     *
     * @return 字典内容；{@code nameOrPath} 为空时返回 null
     * @throws IOException 文件不存在或读取失败时抛出，交由调用方决定如何回退（通常记日志后按无字典处理）
     */
    public static byte[] load(Path configDir, String nameOrPath) throws IOException {
        if (nameOrPath == null || nameOrPath.trim().isEmpty()) {
            return null;
        }
        Path file = resolve(configDir, nameOrPath);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        byte[] bytes = Files.readAllBytes(file);
        return bytes.length == 0 ? null : bytes;
    }
}
