package cn.tohsaka.factory.zstdnet.proxy;

import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet.MagicSniffingInputStream;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet.NoProduceReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证「整条下行无有效产出」时的失败模式判别（修「服务器崩溃被误报为未安装 zstd」）。
 * 同包，可直接调包私有的 {@link LocalZstdNet#classifyNoProduce} 与 {@link MagicSniffingInputStream}。
 */
class LocalZstdNetEarlyDisconnectTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static NoProduceReason classify(byte[] first, int len, long rawSeen) {
        return LocalZstdNet.classifyNoProduce(rawSeen, first, len);
    }

    @Test
    void zeroBytesIsAmbiguousNeutral() {
        // 对端一字节都没回（崩溃 / 重启 / 原版服把魔数当垃圾后静默断）：模糊 → 中性，绝不报「没装」。
        assertEquals(NoProduceReason.NO_RESPONSE, classify(new byte[4], 0, 0L));
    }

    @Test
    void fullZstdMagicMeansBackendCrashedMidHandshake() {
        // 回了完整 zstd 帧魔数却没解出有效内容：后端多半在握手期间崩了。
        byte[] first = bytes(0x28, 0xB5, 0x2F, 0xFD);
        assertEquals(NoProduceReason.SERVER_DROPPED, classify(first, 4, 6L));
    }

    @Test
    void nonZstdReplyMeansNoBackend() {
        // 回了 >=4 个明显非 zstd 的字节（如原版断开包：首字节是小 varint 包长）：确有证据真没装 ZstdNet。
        byte[] first = bytes(0x10, 0x00, 0x05, 0x12);
        assertEquals(NoProduceReason.NO_BACKEND, classify(first, 4, 32L));
    }

    @Test
    void partialMagicPrefixThenMismatchIsNoBackend() {
        // 凑满 4 字节但与魔数不符（28 B5 2F 00）：非 zstd → NO_BACKEND。
        byte[] first = bytes(0x28, 0xB5, 0x2F, 0x00);
        assertEquals(NoProduceReason.NO_BACKEND, classify(first, 4, 4L));
    }

    @Test
    void shortReplyStaysNeutral() {
        // 只回了 1~3 字节就断：太少无法判定，归中性（即便恰好是魔数前缀也不冒认「没装」）。
        assertEquals(NoProduceReason.NO_RESPONSE, classify(bytes(0x28), 1, 1L));
        assertEquals(NoProduceReason.NO_RESPONSE, classify(bytes(0x28, 0xB5), 2, 2L));
        assertEquals(NoProduceReason.NO_RESPONSE, classify(bytes(0x10, 0x00, 0x05), 3, 3L));
    }

    @Test
    void snifferCapturesCountAndFirstFourBytesAcrossBothReadPaths() throws IOException {
        byte[] data = bytes(0x28, 0xB5, 0x2F, 0xFD, 0x01, 0x02, 0x03);
        try (MagicSniffingInputStream sniffer =
                 new MagicSniffingInputStream(new ByteArrayInputStream(data))) {
            // 先逐字节读 1 个（走 read()），再批量读完（走 read(byte[],off,len)）。
            assertEquals(0x28, sniffer.read() & 0xFF);
            byte[] buf = new byte[16];
            int n = sniffer.read(buf, 0, buf.length);
            assertEquals(data.length - 1, n);

            assertEquals(data.length, sniffer.rawSeen());
            assertEquals(4, sniffer.firstLen());
            assertArrayEquals(bytes(0x28, 0xB5, 0x2F, 0xFD), Arrays.copyOf(sniffer.firstBytes(), 4));
        }
    }

    @Test
    void snifferOnEmptyStreamReportsZero() throws IOException {
        try (MagicSniffingInputStream sniffer =
                 new MagicSniffingInputStream(new ByteArrayInputStream(new byte[0]))) {
            assertEquals(-1, sniffer.read());
            assertEquals(0L, sniffer.rawSeen());
            assertEquals(0, sniffer.firstLen());
            assertEquals(NoProduceReason.NO_RESPONSE,
                LocalZstdNet.classifyNoProduce(sniffer.rawSeen(), sniffer.firstBytes(), sniffer.firstLen()));
        }
    }
}
