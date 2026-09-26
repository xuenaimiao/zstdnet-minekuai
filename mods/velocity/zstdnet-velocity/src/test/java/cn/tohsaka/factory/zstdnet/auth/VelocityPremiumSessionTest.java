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

package cn.tohsaka.factory.zstdnet.auth;

import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.LegacyAnswer;
import cn.tohsaka.factory.zstdnet.auth.VelocityPremiumLogic.Route;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityPremiumSessionTest {

    private static final byte[] NONCE = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    @Test
    void completesOnlyAfterBothRoutesAnswered() {
        VelocityPremiumSession session = new VelocityPremiumSession("alice", NONCE, null, 2);
        assertFalse(session.answered().isDone());
        session.onAnswer(Route.PATH, null);
        assertFalse(session.answered().isDone());
        session.onAnswer(Route.LEGACY, null);
        assertTrue(session.answered().isDone());
        assertEquals(LegacyAnswer.NOT_UNDERSTOOD, session.legacyAnswer());
    }

    @Test
    void duplicateAnswersOnSameRouteCountOnce() {
        VelocityPremiumSession session = new VelocityPremiumSession("alice", NONCE, null, 2);
        session.onAnswer(Route.PATH, null);
        session.onAnswer(Route.PATH, null);
        assertFalse(session.answered().isDone());
    }

    @Test
    void legacyAnswerIsRecordedFromOldFabricClient() {
        VelocityPremiumSession session = new VelocityPremiumSession("alice", NONCE, "198.51.100.7", 2);
        assertEquals(LegacyAnswer.PENDING, session.legacyAnswer());
        session.onAnswer(Route.LEGACY, PremiumAuthProtocol.encodeAnswer(true, "mojang"));
        session.onAnswer(Route.PATH, null);
        assertEquals(LegacyAnswer.AUTHENTICATED, session.legacyAnswer());
        assertEquals("198.51.100.7", session.clientIp());
    }

    @Test
    void pathAnswerBodyIsIgnored() {
        VelocityPremiumSession session = new VelocityPremiumSession("alice", NONCE, null, 2);
        session.onAnswer(Route.PATH, PremiumAuthProtocol.encodeAnswer(false, ""));
        assertEquals(LegacyAnswer.PENDING, session.legacyAnswer());
    }

    @Test
    void serverIdIsHexOfNonceAndNonceIsCopied() {
        byte[] nonce = NONCE.clone();
        VelocityPremiumSession session = new VelocityPremiumSession("alice", nonce, null, 2);
        nonce[0] = 99;
        assertEquals("0102030405060708090a0b0c0d0e0f10", session.serverId());
        assertEquals("alice", session.username());
        assertNull(session.clientIp());
    }

    @Test
    void noExpectedAnswersIsImmediatelyComplete() {
        assertTrue(new VelocityPremiumSession("alice", NONCE, null, 0).answered().isDone());
    }
}
