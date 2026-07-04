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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LAN 广播 payload 与原版 LanServerPinger 逐字一致（保证客户端按 ip:port 去重）。
 */
class LanBroadcasterTest {

    @Test
    void payloadMatchesVanillaFormat() {
        assertEquals("[MOTD]My World[/MOTD][AD]61822[/AD]", LanBroadcaster.pingPayload("My World", 61822));
    }

    @Test
    void blankMotdFallsBack() {
        assertEquals("[MOTD]Minecraft[/MOTD][AD]25565[/AD]", LanBroadcaster.pingPayload(null, 25565));
        assertEquals("[MOTD]Minecraft[/MOTD][AD]25565[/AD]", LanBroadcaster.pingPayload("   ", 25565));
    }

    @Test
    void markersAndNewlinesStripped() {
        // motd 里混入 [MOTD]/[AD] 标记或换行不能破坏报文结构。
        assertEquals("[MOTD]ab cd[/MOTD][AD]100[/AD]", LanBroadcaster.pingPayload("a[MOTD]b\nc[AD]d", 100));
    }
}
