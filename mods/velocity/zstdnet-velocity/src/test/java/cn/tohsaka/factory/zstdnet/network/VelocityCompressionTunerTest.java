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

package cn.tohsaka.factory.zstdnet.network;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VelocityCompressionTuner}：对着与 {@code MinecraftCompressorAndLengthEncoder} 同形状的替身验证调阈值时机与降级。
 */
class VelocityCompressionTunerTest {

    private static final int TARGET = VelocityCompressionTuner.ZSTD_COMPRESSION_THRESHOLD;

    private VelocityTestSupport.RecordingReporter reporter;
    private VelocityTestSupport.EventRecorder events;

    @BeforeEach
    void setUp() {
        reporter = new VelocityTestSupport.RecordingReporter();
        events = new VelocityTestSupport.EventRecorder();
    }

    private EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel(events);
        channel.pipeline().addFirst(VelocityCompressionTuner.NAME,
            new VelocityCompressionTuner(TARGET, reporter, new InetSocketAddress("203.0.113.9", 40001)));
        return channel;
    }

    private static void installEncoder(EmbeddedChannel channel, ChannelHandler encoder) {
        channel.pipeline().addLast(VelocityCompressionTuner.ENCODER_NAME, encoder);
    }

    @Test
    void raisesTheThresholdAsSoonAsVelocityEnablesCompression() {
        EmbeddedChannel channel = channel();
        VelocityTestSupport.FakeCompressorEncoder encoder = new VelocityTestSupport.FakeCompressorEncoder(256);
        installEncoder(channel, encoder);

        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);

        assertEquals(TARGET, encoder.threshold());
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        assertEquals(1, reporter.raised.size());
        assertEquals(256, reporter.raised.get(0)[0]);
        assertEquals(TARGET, reporter.raised.get(0)[1]);
        // 事件照常往后传（ViaVersion 等依赖它重排自己的处理器）
        assertEquals(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED, events.events.get(0));
        channel.finishAndReleaseAll();
    }

    @Test
    void keepsVelocityCompressionWhenTheConnectionIsEncrypted() {
        // 在线模式下没走 zstdnet:auth 的连接已开 AES：密文 ZSTD 压不动，去掉 zlib 只会更费流量。
        EmbeddedChannel channel = channel();
        channel.pipeline().addLast(VelocityCompressionTuner.CIPHER_ENCODER_NAME, new ChannelOutboundHandlerAdapter());
        VelocityTestSupport.FakeCompressorEncoder encoder = new VelocityTestSupport.FakeCompressorEncoder(256);
        installEncoder(channel, encoder);

        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);

        assertEquals(256, encoder.threshold());
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        assertEquals(0, reporter.raised.size());
        assertEquals(1, reporter.keptEncrypted.size());
        assertEquals(0, reporter.warnings.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void waitsThroughUnrelatedEventsUntilTheEncoderAppears() {
        EmbeddedChannel channel = channel();

        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.ENCRYPTION_ENABLED);
        assertNotNull(channel.pipeline().get(VelocityCompressionTuner.NAME));

        VelocityTestSupport.FakeCompressorEncoder encoder = new VelocityTestSupport.FakeCompressorEncoder(256);
        installEncoder(channel, encoder);
        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);

        assertEquals(TARGET, encoder.threshold());
        assertEquals(2, events.events.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void stepsAsideWhenVelocityDisablesCompression() {
        EmbeddedChannel channel = channel();

        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_DISABLED);

        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        assertTrue(reporter.raised.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test
    void leavesADisabledOrAlreadyHighEncoderAlone() {
        EmbeddedChannel disabled = channel();
        VelocityTestSupport.FakeCompressorEncoder off = new VelocityTestSupport.FakeCompressorEncoder(-1);
        installEncoder(disabled, off);
        disabled.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);
        assertEquals(-1, off.threshold());
        assertNull(disabled.pipeline().get(VelocityCompressionTuner.NAME));
        disabled.finishAndReleaseAll();

        EmbeddedChannel high = channel();
        VelocityTestSupport.FakeCompressorEncoder already = new VelocityTestSupport.FakeCompressorEncoder(TARGET * 2);
        installEncoder(high, already);
        high.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);
        assertEquals(TARGET * 2, already.threshold());
        high.finishAndReleaseAll();

        assertTrue(reporter.raised.isEmpty());
        assertTrue(reporter.warnings.isEmpty());
    }

    @Test
    void fallsBackToCheckingOnWritesWhenNoEventArrives() {
        EmbeddedChannel channel = channel();
        channel.writeOutbound("encryption request");
        assertNotNull(channel.pipeline().get(VelocityCompressionTuner.NAME));

        VelocityTestSupport.FakeCompressorEncoder encoder = new VelocityTestSupport.FakeCompressorEncoder(256);
        installEncoder(channel, encoder);
        channel.writeOutbound("login success");

        assertEquals(TARGET, encoder.threshold());
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        // 写出的消息一个不少、顺序不变
        assertEquals("encryption request", channel.readOutbound());
        assertEquals("login success", channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void givesUpAfterManyWritesWithoutAnEncoder() {
        EmbeddedChannel channel = channel();
        for (int i = 0; i < VelocityCompressionTuner.MAX_WRITES_WITHOUT_ENCODER; i++) {
            assertNotNull(channel.pipeline().get(VelocityCompressionTuner.NAME), "still waiting at write " + i);
            channel.writeOutbound("packet " + i);
        }
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        channel.finishAndReleaseAll();
    }

    @Test
    void patchesTheFieldDirectlyWhenThereIsNoSetter() {
        EmbeddedChannel channel = channel();
        VelocityTestSupport.FieldOnlyEncoder encoder = new VelocityTestSupport.FieldOnlyEncoder(256);
        installEncoder(channel, encoder);

        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);

        assertEquals(TARGET, encoder.threshold());
        assertEquals(1, reporter.raised.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void unknownEncoderShapeWarnsAndKeepsTheConnectionWorking() {
        EmbeddedChannel channel = channel();
        installEncoder(channel, new VelocityTestSupport.UnknownEncoder());

        channel.pipeline().fireUserEventTriggered(VelocityTestSupport.FakeConnectionEvent.COMPRESSION_ENABLED);

        assertTrue(reporter.warnings.contains("compression-reflect"));
        assertTrue(reporter.raised.isEmpty());
        assertNull(channel.pipeline().get(VelocityCompressionTuner.NAME));
        assertEquals(1, events.events.size());
        channel.writeOutbound("still flowing");
        assertEquals("still flowing", channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void raiseThresholdReportsEachOutcome() throws Exception {
        assertEquals(VelocityCompressionTuner.Outcome.Kind.RAISED,
            VelocityCompressionTuner.raiseThreshold(new VelocityTestSupport.FakeCompressorEncoder(256), TARGET).kind);
        assertEquals(VelocityCompressionTuner.Outcome.Kind.DISABLED,
            VelocityCompressionTuner.raiseThreshold(new VelocityTestSupport.FakeCompressorEncoder(-1), TARGET).kind);
        assertEquals(VelocityCompressionTuner.Outcome.Kind.ALREADY_HIGH,
            VelocityCompressionTuner.raiseThreshold(new VelocityTestSupport.FakeCompressorEncoder(TARGET), TARGET).kind);
        assertEquals(VelocityCompressionTuner.Outcome.Kind.UNSUPPORTED,
            VelocityCompressionTuner.raiseThreshold(new VelocityTestSupport.UnknownEncoder(), TARGET).kind);
    }
}
