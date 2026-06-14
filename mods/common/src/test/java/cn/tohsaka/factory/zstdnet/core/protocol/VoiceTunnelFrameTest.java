package cn.tohsaka.factory.zstdnet.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTunnelFrameTest {

    @Test
    void wrapThenParseRoundTrips() {
        byte[] payload = "voice-packet-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] frame = VoiceTunnelFrame.wrap(7, payload, 0, payload.length);

        assertTrue(VoiceTunnelFrame.isFrame(frame, 0, frame.length));
        assertEquals(7, VoiceTunnelFrame.channelId(frame, 0));

        byte[] recovered = Arrays.copyOfRange(frame, VoiceTunnelFrame.payloadOffset(), frame.length);
        assertArrayEquals(payload, recovered);
    }

    @Test
    void emptyPayloadWraps() {
        byte[] frame = VoiceTunnelFrame.wrap(0, new byte[0], 0, 0);
        assertEquals(VoiceTunnelFrame.HEADER_LEN, frame.length);
        assertTrue(VoiceTunnelFrame.isFrame(frame, 0, frame.length));
        assertEquals(0, VoiceTunnelFrame.channelId(frame, 0));
    }

    @Test
    void rawGamePacketIsNotMistakenForFrame() {
        // A Sable / vanilla-style raw UDP payload must never be parsed as a ZV1 frame.
        byte[] raw = new byte[]{0x10, 0x00, 0x42, (byte) 0xFF, 0x01, 0x02, 0x03, 0x04};
        assertFalse(VoiceTunnelFrame.isFrame(raw, 0, raw.length));
    }

    @Test
    void magicPrefixButWrongVersionRejected() {
        byte[] buf = new byte[]{'Z', 'V', '1', 0, (byte) 99, 0, 1, 2};
        assertFalse(VoiceTunnelFrame.isFrame(buf, 0, buf.length));
    }

    @Test
    void tooShortRejected() {
        byte[] buf = new byte[]{'Z', 'V', '1', 0, 1};
        assertFalse(VoiceTunnelFrame.isFrame(buf, 0, buf.length));
    }

    @Test
    void highChannelIdRoundTrips() {
        byte[] frame = VoiceTunnelFrame.wrap(255, new byte[]{9}, 0, 1);
        assertEquals(255, VoiceTunnelFrame.channelId(frame, 0));
    }
}
