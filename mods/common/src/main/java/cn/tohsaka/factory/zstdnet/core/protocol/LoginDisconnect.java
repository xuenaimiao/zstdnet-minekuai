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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 构造「登录态断开（Login Disconnect）」数据包（单一真源）。
 *
 * <p>登录态客户端发包 id 恒为 {@code 0x00}，载荷为一个 JSON 文本组件（长度前缀的 UTF-8 字符串）。
 * 此格式在本仓覆盖的所有 MC 版本（1.18.2 ~ 26.1）登录阶段一致，且发生在压缩 / 加密协商之前，
 * 因此可以明文直接写给尚处登录态的客户端，由原版断开界面渲染我方给出的原因。
 *
 * <p>服务端（{@code ServerProxyRuntime}，拒绝裸登录）与客户端本地代理（{@code LocalZstdNet}，
 * 探测到对端没有 ZstdNet 服务端 / 后端不可达时）共用这一份编码逻辑。
 */
public final class LoginDisconnect {
    private LoginDisconnect() {
    }

    /**
     * 把 {@code message} 包成完整的登录态断开数据包字节（含包长前缀），可直接写入 socket。
     */
    public static byte[] buildPacket(String message) {
        byte[] componentJson = textComponentJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] packetId = VarIntCodec.encode(0);
        byte[] componentLength = VarIntCodec.encode(componentJson.length);

        byte[] payload = new byte[packetId.length + componentLength.length + componentJson.length];
        int offset = 0;
        System.arraycopy(packetId, 0, payload, offset, packetId.length);
        offset += packetId.length;
        System.arraycopy(componentLength, 0, payload, offset, componentLength.length);
        offset += componentLength.length;
        System.arraycopy(componentJson, 0, payload, offset, componentJson.length);

        byte[] packetLength = VarIntCodec.encode(payload.length);
        byte[] packet = new byte[packetLength.length + payload.length];
        System.arraycopy(packetLength, 0, packet, 0, packetLength.length);
        System.arraycopy(payload, 0, packet, packetLength.length, payload.length);
        return packet;
    }

    /**
     * 向尚处登录态的客户端写出一个断开包并 flush；失败只吞掉（连接随后会被关闭）。
     *
     * @return 是否成功写出
     */
    public static boolean trySend(OutputStream out, String message) {
        if (out == null || message == null || message.trim().isEmpty()) {
            return false;
        }
        try {
            out.write(buildPacket(message));
            out.flush();
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String textComponentJson(String text) {
        return "{\"text\":\"" + escapeJson(text) + "\"}";
    }

    private static String escapeJson(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default: {
                    if (ch < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                    break;
                }
            }
        }
        return builder.toString();
    }
}
