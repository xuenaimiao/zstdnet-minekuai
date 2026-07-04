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

package cn.tohsaka.factory.zstdnet.core.transform;

/**
 * 变换能力的握手协商（单一真源）。客户端在握手 host 后缀追加一个 {@code \0zstdnet-xform=<版本>} 标记
 * advertise 自己支持的最高变换版本；服务端读取该标记决定是否对下行变换，并在转发后端前剥掉它
 * （沿用 {@code zstdnet-real-ip=} 的同款 host 后缀信道）。
 *
 * <p>对未升级端逐字节兼容：客户端默认不 advertise（无标记）；服务端遇到无标记/不识别即按原样处理。
 */
public final class TransformHandshake {
    /** host 后缀里的能力标记键（其后紧跟版本整数）。 */
    public static final String MARKER = "zstdnet-xform=";

    private TransformHandshake() {
    }

    /** 客户端 advertise 用的 host 后缀片段：{@code \0zstdnet-xform=<version>}。 */
    public static String advertiseSuffix(int version) {
        return "\0" + MARKER + version;
    }

    /**
     * 从握手 host 解析客户端 advertise 的变换版本。
     *
     * @return 版本号（≥1）；无标记 / 非法 / 0 表示不启用
     */
    public static int parseVersion(String host) {
        if (host == null) {
            return 0;
        }
        int idx = host.indexOf(MARKER);
        if (idx < 0) {
            return 0;
        }
        int valStart = idx + MARKER.length();
        int valEnd = host.indexOf('\0', valStart);
        if (valEnd < 0) {
            valEnd = host.length();
        }
        try {
            return Math.max(0, Integer.parseInt(host.substring(valStart, valEnd).trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 剥掉 host 中的 {@code \0zstdnet-xform=<版本>} 字段（连同其前导分隔符），其余字段（含 FML 标记、
     * {@code zstdnet-real-ip=}）保持不变与原顺序。无标记时原样返回。
     */
    public static String strip(String host) {
        if (host == null) {
            return null;
        }
        int idx = host.indexOf(MARKER);
        if (idx < 0) {
            return host;
        }
        int fieldStart = (idx > 0 && host.charAt(idx - 1) == '\0') ? idx - 1 : idx;
        int valEnd = host.indexOf('\0', idx + MARKER.length());
        if (valEnd < 0) {
            valEnd = host.length();
        }
        return host.substring(0, fieldStart) + host.substring(valEnd);
    }
}
