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

package cn.tohsaka.factory.zstdnet.proxy;

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.compress.ZstdStreams;
import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「对端会不会说 ZSTD」探测（raw_fallback 判据）：
 * zstd 服务端（压缩 status 往返）→ ZSTD；原版端口（原样回 status / 沉默）→ NO_ZSTD；连不上 → UNREACHABLE。
 */
class ZstdProbeTest {

    private static final String STATUS_JSON =
        "{\"version\":{\"name\":\"1.20.1\",\"protocol\":763},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"probe\"}}";

    private static byte[] statusResponsePayload() {
        byte[] json = STATUS_JSON.getBytes(StandardCharsets.UTF_8);
        return ByteArrayOps.concat(VarIntCodec.encode(0), VarIntCodec.encode(json.length), json);
    }

    /** 模拟 ZstdNet 服务端：解压上行读掉握手 + status 请求，回一帧压缩的 status 响应。 */
    @Test
    void zstdSpeakingServerIsDetected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    InputStream zstdIn = ZstdStreams.newDecompressor(client.getInputStream(), CompressionOptions.none(), null);
                    byte[] handshake = PacketIo.readPacket(zstdIn);
                    assertTrue(handshake.length > 0);
                    byte[] statusRequest = PacketIo.readPacket(zstdIn);
                    assertEquals(1, statusRequest.length);

                    OutputStream zstdOut = ZstdStreams.newCompressor(client.getOutputStream(), 3, CompressionOptions.none(), false);
                    PacketIo.writePacket(zstdOut, statusResponsePayload());
                    zstdOut.flush();
                    // 等对端读完并关连接（EOF），避免过早关闭截断响应。
                    while (client.getInputStream().read() >= 0) {
                        // drain until EOF
                    }
                } catch (Exception ignored) {
                }
            }, "probe-test-zstd-server");
            serverThread.setDaemon(true);
            serverThread.start();

            assertEquals(ZstdProbe.Result.ZSTD, ZstdProbe.probe("127.0.0.1", server.getLocalPort(), 2000, 3000));
        }
    }

    /** 模拟原版端口：把压缩字节当垃圾、按原版逻辑回一个未压缩 status 响应——解压必失败 → NO_ZSTD。 */
    @Test
    void rawStatusServerIsNoZstd() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(2000);
                    byte[] buf = new byte[512];
                    int ignored = client.getInputStream().read(buf);
                    PacketIo.writePacket(client.getOutputStream(), statusResponsePayload());
                    client.getOutputStream().flush();
                } catch (Exception ignoredEx) {
                }
            }, "probe-test-raw-server");
            serverThread.setDaemon(true);
            serverThread.start();

            assertEquals(ZstdProbe.Result.NO_ZSTD, ZstdProbe.probe("127.0.0.1", server.getLocalPort(), 2000, 2000));
        }
    }

    /** 模拟收下连接但一声不吭的对端（原版服把垃圾长度当作待续包）：限时沉默 → NO_ZSTD。 */
    @Test
    void silentServerIsNoZstd() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                }
            }, "probe-test-silent-server");
            serverThread.setDaemon(true);
            serverThread.start();

            assertEquals(ZstdProbe.Result.NO_ZSTD, ZstdProbe.probe("127.0.0.1", server.getLocalPort(), 1000, 500));
        }
    }

    /** TCP 都连不上（端口无人监听）→ UNREACHABLE（调用方保持 ZSTD 代理路径以给出友好报错）。 */
    @Test
    void unreachablePortIsUnreachable() throws Exception {
        int freePort;
        try (ServerSocket temp = new ServerSocket(0)) {
            freePort = temp.getLocalPort();
        }
        assertEquals(ZstdProbe.Result.UNREACHABLE, ZstdProbe.probe("127.0.0.1", freePort, 1000, 500));
    }

    @Test
    void statusResponseValidation() {
        assertTrue(ZstdProbe.isStatusResponse(statusResponsePayload()));
        assertFalse(ZstdProbe.isStatusResponse(null));
        assertFalse(ZstdProbe.isStatusResponse(new byte[0]));
        // 包 id 非 0
        assertFalse(ZstdProbe.isStatusResponse(new byte[]{0x01, 0x02, 'h', 'i'}));
        // 声明的 JSON 长度超过实际字节数
        assertFalse(ZstdProbe.isStatusResponse(new byte[]{0x00, 0x7F, 'x'}));
    }
}
