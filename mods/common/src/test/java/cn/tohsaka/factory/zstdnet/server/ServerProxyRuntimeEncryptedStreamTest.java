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

import cn.tohsaka.factory.zstdnet.core.compress.CompressionOptions;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.platform.Platform;
import cn.tohsaka.factory.zstdnet.platform.Platforms;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端回归（真实 {@link ServerProxyRuntime} + 真实 {@link LocalZstdNet} + 假后端）：
 * 后端在登录握手里先发 Encryption Request、此后只发「密文」时，客户端必须及时收到，而不是被下行帧缓冲扣住。
 *
 * <p>这是 AutoModpack 5.x HOLEPUNCH 在同端口上自带一遍原版加密握手、再在 AES 之上跑 TLS 的缩影：
 * 修复前，服务端的区块引用缓存（chunk_cache=auto，默认开）把密文当成永远收不齐的 MC 大帧憋住，
 * 双方互等到 AMP 的 10 秒 TLS 握手超时。
 */
class ServerProxyRuntimeEncryptedStreamTest {

    private static final int PROTOCOL_1_21_1 = 767;
    /** 假「密文」：每块的前两字节解成 VarInt 999，而块本身远短于 999——按 MC 帧格式永远凑不齐。 */
    private static final int[] CIPHER_CHUNK_SIZES = {52, 31, 47};

    private Platform previousPlatform;
    private ServerProxyRuntime runtime;
    private LocalZstdNet.ProxyHandle proxy;
    private ServerSocket backend;

    @BeforeEach
    void setUp(@TempDir Path configDir) {
        previousPlatform = Platforms.get();
        Platforms.set(new Platform() {
            @Override
            public Path configDir() {
                return configDir;
            }

            @Override
            public boolean isClient() {
                return false;
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception ignored) {
            }
        }
        if (runtime != null) {
            runtime.stop();
        }
        if (backend != null) {
            try {
                backend.close();
            } catch (IOException ignored) {
            }
        }
        DedicatedAutoPortState.clear();
        Platforms.set(previousPlatform);
        // 还原 LocalZstdNet 的客户端缓存静态配置到默认值（开启 + 落盘持久化 + 默认预算），免得污染同 JVM 的其他用例。
        LocalZstdNet.configureClientCache(true, true, 0L);
    }

    @Test
    void ciphertextAfterEncryptionRequestReachesTheClientPromptly() throws Exception {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));
        int listenPort = freePort();

        DedicatedAutoPortState.set(new AutoPortPlan("127.0.0.1", listenPort, "127.0.0.1", backend.getLocalPort()));
        runtime = new ServerProxyRuntime();
        runtime.start(backend.getLocalPort());
        assertTrue(runtime.isRunning(), "server proxy should be listening");

        AtomicReference<Throwable> backendFailure = new AtomicReference<>();
        byte[][] sentCipher = new byte[CIPHER_CHUNK_SIZES.length][];
        Thread backendThread = new Thread(() -> runFakeBackend(sentCipher, backendFailure), "fake-amp-backend");
        backendThread.setDaemon(true);
        backendThread.start();

        // 与真实客户端一致：默认开启区块引用缓存协商，ZSTD 模式连服务端入口。
        LocalZstdNet.configureClientCache(true, false, 0L);
        proxy = LocalZstdNet.start(
            "127.0.0.1", listenPort,
            "127.0.0.1", listenPort,
            "127.0.0.1", listenPort,
            3, CompressionOptions.none(), LocalZstdNet.Mode.ZSTD);

        try (Socket game = new Socket("127.0.0.1", proxy.localPort())) {
            game.setSoTimeout(5000);
            OutputStream out = game.getOutputStream();
            InputStream in = game.getInputStream();

            PacketIo.writePacket(out, handshake(PROTOCOL_1_21_1, "localhost", 25565, 2));
            PacketIo.writePacket(out, loginStart("holepunch"));
            out.flush();

            // 1) 首帧：Encryption Request（packetId 0x01）。
            byte[] first = PacketIo.readPacket(in);
            assertEquals(0x01, first[0], "first downstream packet should be the Encryption Request");

            // 2) 客户端回「加密响应」，此后双方都进入「密文」。
            out.write(new byte[16]);
            out.flush();

            // 3) 后端发来的密文必须在远小于 AMP 的 10 秒握手超时的时间内到达，且逐字节一致。
            long start = System.nanoTime();
            int total = 0;
            for (int size : CIPHER_CHUNK_SIZES) {
                total += size;
            }
            byte[] received = new byte[total];
            new DataInputStream(in).readFully(received);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            backendThread.join(3000);
            for (byte[] chunk : sentCipher) {
                expected.write(chunk, 0, chunk.length);
            }
            assertArrayEquals(expected.toByteArray(), received, "ciphertext must arrive byte-exact");
            assertTrue(elapsedMs < 3000, "ciphertext was held back for " + elapsedMs + " ms");
        }
        assertNull(backendFailure.get(), () -> "fake backend failed: " + backendFailure.get());
    }

    // ---- fake AMP-like backend: Encryption Request first, then "AES ciphertext" ----

    private void runFakeBackend(byte[][] sentCipher, AtomicReference<Throwable> failure) {
        try (Socket socket = backend.accept()) {
            socket.setSoTimeout(8000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            PacketIo.readPacket(in); // Handshake
            PacketIo.readPacket(in); // Login Start

            PacketIo.writePacket(out, concat(VarIntCodec.encode(0x01), body(120, 3)));
            out.flush();

            new DataInputStream(in).readFully(new byte[16]); // 「加密响应」

            for (int i = 0; i < CIPHER_CHUNK_SIZES.length; i++) {
                byte[] chunk = new byte[CIPHER_CHUNK_SIZES[i]];
                chunk[0] = (byte) 0xE7;
                chunk[1] = 0x07; // 开头恰好解成 VarInt 999
                for (int j = 2; j < chunk.length; j++) {
                    chunk[j] = (byte) (i * 17 + j * 5 + 1);
                }
                sentCipher[i] = chunk;
                out.write(chunk);
                out.flush();
                Thread.sleep(30);
            }
            // 保持连接直到客户端读完（客户端关闭后这里读到 EOF）。
            while (in.read() >= 0) {
                // drain
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    // ---- helpers ----

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static byte[] handshake(int protocol, String host, int port, int nextState) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        return concat(
            VarIntCodec.encode(0x00),
            VarIntCodec.encode(protocol),
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            new byte[]{(byte) (port >>> 8), (byte) port},
            VarIntCodec.encode(nextState));
    }

    private static byte[] loginStart(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        return concat(VarIntCodec.encode(0x00), VarIntCodec.encode(nameBytes.length), nameBytes, new byte[16]);
    }

    private static byte[] body(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (seed * 31 + i);
        }
        return b;
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.write(a, 0, a.length);
        }
        return out.toByteArray();
    }
}
