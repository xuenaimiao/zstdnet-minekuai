package cn.tohsaka.factory.zstdnet.proxy;

import cn.tohsaka.factory.zstdnet.core.protocol.VoiceTunnelFrame;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalZstdNetUdpPassthroughTest {

    @Test
    void localProxyForwardsUdpOnTcpPort() throws Exception {
        try (DatagramSocket echoServer = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            echoServer.setSoTimeout(5000);
            AtomicReference<Exception> echoFailure = new AtomicReference<>();
            Thread echoThread = new Thread(() -> echoPackets(echoServer, 1, echoFailure), "zstdnet-test-udp-echo");
            echoThread.setDaemon(true);
            echoThread.start();

            try (LocalZstdNet.ProxyHandle proxy = LocalZstdNet.start(
                "127.0.0.1",
                echoServer.getLocalPort(),
                3,
                LocalZstdNet.Mode.RAW
            ); DatagramSocket client = new DatagramSocket()) {
                client.setSoTimeout(5000);
                byte[] payload = "sable-auth".getBytes(StandardCharsets.UTF_8);
                client.send(new DatagramPacket(
                    payload,
                    payload.length,
                    InetAddress.getByName("127.0.0.1"),
                    proxy.localPort()
                ));

                byte[] received = new byte[payload.length];
                DatagramPacket response = new DatagramPacket(received, received.length);
                client.receive(response);

                assertArrayEquals(payload, Arrays.copyOf(response.getData(), response.getLength()));
            }
            echoThread.join(1000);
            assertNull(echoFailure.get());
        }
    }

    @Test
    void localProxyKeepsSeparateUdpClientSessions() throws Exception {
        try (DatagramSocket echoServer = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            echoServer.setSoTimeout(5000);
            AtomicReference<Exception> echoFailure = new AtomicReference<>();
            Thread echoThread = new Thread(() -> echoPackets(echoServer, 2, echoFailure), "zstdnet-test-udp-echo-multi");
            echoThread.setDaemon(true);
            echoThread.start();

            try (LocalZstdNet.ProxyHandle proxy = LocalZstdNet.start(
                "127.0.0.1",
                echoServer.getLocalPort(),
                3,
                LocalZstdNet.Mode.RAW
            ); DatagramSocket firstClient = new DatagramSocket(); DatagramSocket secondClient = new DatagramSocket()) {
                firstClient.setSoTimeout(5000);
                secondClient.setSoTimeout(5000);

                byte[] firstPayload = "sable-session-a".getBytes(StandardCharsets.UTF_8);
                byte[] secondPayload = "voice-session-b".getBytes(StandardCharsets.UTF_8);
                InetAddress loopback = InetAddress.getByName("127.0.0.1");

                firstClient.send(new DatagramPacket(firstPayload, firstPayload.length, loopback, proxy.localPort()));
                secondClient.send(new DatagramPacket(secondPayload, secondPayload.length, loopback, proxy.localPort()));

                assertArrayEquals(firstPayload, receiveBytes(firstClient, firstPayload.length));
                assertArrayEquals(secondPayload, receiveBytes(secondClient, secondPayload.length));
            }
            echoThread.join(1000);
            assertNull(echoFailure.get());
        }
    }

    @Test
    void voiceTunnelWrapsAndUnwrapsThroughEntryPort() throws Exception {
        // 模拟服务端入口端口：收到 ZV1 语音帧（channelId 0）后原样回送，
        // 等价于「服务端剥头 -> 后端回显 payload -> 重新打同 channelId 头」。客户端应剥头后把 payload 投回语音 mod。
        try (DatagramSocket entry = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            entry.setSoTimeout(5000);
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread entryThread = new Thread(() -> echoVoiceFrame(entry, failure), "zstdnet-test-voice-entry");
            entryThread.setDaemon(true);
            entryThread.start();

            int voicePort = freeUdpPort();
            try (LocalZstdNet.ProxyHandle proxy = LocalZstdNet.start(
                "127.0.0.1",
                entry.getLocalPort(),
                3,
                LocalZstdNet.Mode.RAW
            ); DatagramSocket voiceMod = new DatagramSocket()) {
                proxy.armVoicePorts("tunnel", List.of(voicePort));
                voiceMod.setSoTimeout(5000);

                byte[] payload = "plasmo-voice-udp".getBytes(StandardCharsets.UTF_8);
                voiceMod.send(new DatagramPacket(
                    payload,
                    payload.length,
                    InetAddress.getByName("127.0.0.1"),
                    voicePort
                ));

                assertArrayEquals(payload, receiveBytes(voiceMod, payload.length));
            }
            entryThread.join(1000);
            assertNull(failure.get());
        }
    }

    private static void echoVoiceFrame(DatagramSocket socket, AtomicReference<Exception> failure) {
        try {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            // 入口端口收到的应该是带 ZV1 头的语音帧，而不是裸 UDP。
            assertTrue(VoiceTunnelFrame.isFrame(packet.getData(), packet.getOffset(), packet.getLength()));
            socket.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
        } catch (Exception e) {
            failure.set(e);
        }
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    private static byte[] receiveBytes(DatagramSocket socket, int expectedLength) throws Exception {
        byte[] received = new byte[expectedLength];
        DatagramPacket response = new DatagramPacket(received, received.length);
        socket.receive(response);
        return Arrays.copyOf(response.getData(), response.getLength());
    }

    private static void echoPackets(DatagramSocket socket, int packetCount, AtomicReference<Exception> failure) {
        try {
            for (int i = 0; i < packetCount; i++) {
                byte[] buffer = new byte[256];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                socket.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
            }
        } catch (Exception e) {
            failure.set(e);
        }
    }
}
