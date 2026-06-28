package cn.tohsaka.factory.zstdnet.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumAuthProtocolTest {

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void challengeRoundTrips() {
        byte[] nonce = hexToBytes("00112233445566778899aabbccddeeff");
        byte[] payload = PremiumAuthProtocol.encodeChallenge(nonce);
        assertArrayEquals(nonce, PremiumAuthProtocol.decodeChallenge(payload));
    }

    @Test
    void serverIdDerivationIsDeterministicAndHex() {
        byte[] nonce = hexToBytes("0102030405060708090a0b0c0d0e0f10");
        String a = PremiumAuthProtocol.serverIdFromNonce(nonce);
        String b = PremiumAuthProtocol.serverIdFromNonce(nonce.clone());
        assertEquals(a, b);
        assertEquals("0102030405060708090a0b0c0d0e0f10", a);
    }

    @Test
    void answerRoundTrips() {
        byte[] payload = PremiumAuthProtocol.encodeAnswer(true, "mojang");
        PremiumAuthProtocol.Answer answer = PremiumAuthProtocol.decodeAnswer(payload);
        assertTrue(answer.authenticated());
        assertEquals("mojang", answer.source());
    }

    @Test
    void unauthenticatedAnswerRoundTrips() {
        byte[] payload = PremiumAuthProtocol.encodeAnswer(false, "");
        PremiumAuthProtocol.Answer answer = PremiumAuthProtocol.decodeAnswer(payload);
        assertFalse(answer.authenticated());
    }

    @Test
    void malformedPayloadsRejected() {
        assertNull(PremiumAuthProtocol.decodeChallenge(null));
        assertNull(PremiumAuthProtocol.decodeChallenge(new byte[0]));
        assertNull(PremiumAuthProtocol.decodeChallenge(new byte[]{0x00})); // wrong version + truncated
        assertFalse(PremiumAuthProtocol.decodeAnswer(new byte[0]).authenticated());
    }

    @Test
    void wrongWireVersionRejected() {
        byte[] payload = PremiumAuthProtocol.encodeChallenge(new byte[]{1, 2, 3, 4});
        payload[0] = 99; // corrupt version byte
        assertNull(PremiumAuthProtocol.decodeChallenge(payload));
    }

    @Test
    void channelPathServerIdRoundTrips() {
        byte[] nonce = hexToBytes("00112233445566778899aabbccddeeff");
        String serverId = PremiumAuthProtocol.serverIdFromNonce(nonce);
        String path = PremiumAuthProtocol.channelPathWithServerId(serverId);
        assertEquals("auth/00112233445566778899aabbccddeeff", path);
        assertEquals(serverId, PremiumAuthProtocol.serverIdFromChannelPath(path));
    }

    @Test
    void channelPathRejectsForeignOrMalformed() {
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath(null));
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath("auth")); // no nonce
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath("other/00ff")); // wrong prefix
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath("auth/")); // empty nonce
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath("auth/NOTHEX")); // non-hex (also upper)
        assertNull(PremiumAuthProtocol.serverIdFromChannelPath("auth/00ff/x")); // illegal trailing
    }
}
