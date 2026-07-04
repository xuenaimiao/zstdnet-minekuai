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

package cn.tohsaka.factory.zstdnet.core.protocol;

/**
 * 「单端口语音隧道」的 UDP 帧格式（ZV1）。
 *
 * <p>为了把各类独立端口语音 mod（Simple Voice Chat / Plasmo Voice 等）的 UDP 也塞进 ZstdNet 唯一的
 * 公网入口端口，客户端会把语音 UDP 包加上一个固定小包头后发往入口端口，服务端按 {@code channelId} 拆分
 * （demux）转发给后端对应语音端口；回程反向打标。</p>
 *
 * <p>包头共 {@value #HEADER_LEN} 字节：</p>
 * <pre>
 *   byte[0..3]  MAGIC = 'Z' 'V' '1' 0x00
 *   byte[4]     version (= {@value #VERSION})
 *   byte[5]     channelId (0..255，对应服务端探测出的有序语音端口下标)
 *   byte[6..]   payload (原始语音 UDP 负载，原样不压缩)
 * </pre>
 *
 * <p><b>向后兼容关键</b>：游戏自身的同端口 UDP（Sable / 机械动力：航空学等）以及旧版客户端的 UDP
 * <b>永不带这个包头</b>。服务端在入口端口收到包时先比对 MAGIC+version：匹配才当语音帧拆分，否则一律按
 * 现状裸透传给后端游戏端口。6 字节固定魔数与游戏/语音负载碰撞概率可忽略。</p>
 */
public final class VoiceTunnelFrame {
    /** 包头长度（字节）。 */
    public static final int HEADER_LEN = 6;
    /** 当前线格式版本。 */
    public static final byte VERSION = 1;
    /** 单条隧道最多支持的通道数（channelId 用 1 字节）。 */
    public static final int MAX_CHANNELS = 256;

    private static final byte M0 = 'Z';
    private static final byte M1 = 'V';
    private static final byte M2 = '1';
    private static final byte M3 = 0;

    private VoiceTunnelFrame() {
    }

    /**
     * 判断 {@code buf[off..off+len)} 是否为一个合法 ZV1 语音帧（MAGIC + 已知 version + 至少有包头）。
     */
    public static boolean isFrame(byte[] buf, int off, int len) {
        if (buf == null || len < HEADER_LEN || off < 0 || off + len > buf.length) {
            return false;
        }
        return buf[off] == M0
            && buf[off + 1] == M1
            && buf[off + 2] == M2
            && buf[off + 3] == M3
            && buf[off + 4] == VERSION;
    }

    /**
     * 读取帧中的 channelId（0..255）。调用前应先 {@link #isFrame(byte[], int, int)} 校验。
     */
    public static int channelId(byte[] buf, int off) {
        return buf[off + 5] & 0xFF;
    }

    /** 帧内 payload 的起始偏移（相对帧首）。 */
    public static int payloadOffset() {
        return HEADER_LEN;
    }

    /**
     * 把一段语音负载包成 ZV1 帧：{@code header(channelId) + payload[payloadOff..payloadOff+payloadLen)}。
     *
     * @param channelId 0..255
     * @return 新分配的帧字节数组（长度 = {@value #HEADER_LEN} + payloadLen）
     */
    public static byte[] wrap(int channelId, byte[] payload, int payloadOff, int payloadLen) {
        if (channelId < 0 || channelId >= MAX_CHANNELS) {
            throw new IllegalArgumentException("channelId out of range: " + channelId);
        }
        byte[] frame = new byte[HEADER_LEN + payloadLen];
        frame[0] = M0;
        frame[1] = M1;
        frame[2] = M2;
        frame[3] = M3;
        frame[4] = VERSION;
        frame[5] = (byte) channelId;
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOff, frame, HEADER_LEN, payloadLen);
        }
        return frame;
    }
}
