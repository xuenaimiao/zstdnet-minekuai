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

package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.auth.PremiumAuthProtocol;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「登录阶段正版验证」——客户端 coremod 钩子（MC 1.21.1，现代登录流程；与 NeoForge 26.1 同构）。
 * <p>
 * 由 {@code coremods/zstdnet_premium_auth.js} 注入 {@code ClientHandshakePacketListenerImpl#handleCustomQuery}
 * 方法首部：识别信道 {@code zstdnet:auth/<serverId>}（nonce 在 {@code ResourceLocation} 路径，payload 被原版丢弃）
 * → 后台 {@code joinServer}（access token 不出客户端）→ 回一条空应答（沿用同一事务号）。
 */
public final class PremiumAuthClientHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zstdnet-premium-auth-client");
        t.setDaemon(true);
        return t;
    });

    private PremiumAuthClientHooks() {
    }

    /**
     * 注入在 {@code handleCustomQuery} 首部。返回 {@code true} 表示我方查询、已接管（跳过原版），
     * {@code false} 交还原版。
     */
    public static boolean handleClientQuery(Connection connection, ClientboundCustomQueryPacket packet) {
        if (packet == null) {
            return false;
        }
        CustomQueryPayload payload = packet.payload();
        Identifier id = payload != null ? payload.id() : null;
        if (id == null || !PremiumAuthProtocol.CHANNEL_NAMESPACE.equals(id.getNamespace())) {
            return false;
        }
        String serverId = PremiumAuthProtocol.serverIdFromChannelPath(id.getPath());
        if (serverId == null) {
            return false;
        }
        int transactionId = packet.transactionId();
        EXECUTOR.execute(() -> {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                User user = minecraft.getUser();
                minecraft.services().sessionService().joinServer(
                    user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Throwable t) {
                LOGGER.debug("[zstdnet-client] premium joinServer failed (offline session?): {}", t.toString());
            } finally {
                connection.send(new ServerboundCustomQueryAnswerPacket(transactionId, null));
            }
        });
        return true;
    }
}
