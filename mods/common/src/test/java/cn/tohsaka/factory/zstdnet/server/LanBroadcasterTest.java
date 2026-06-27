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
