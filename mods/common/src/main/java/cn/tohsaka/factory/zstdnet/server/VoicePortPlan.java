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

import java.util.List;

/**
 * 一次语音端口计划：传输方式 + 探测到的需要单独处理的语音端口（有序，下标即隧道 channelId）。
 *
 * <p>服务端把它下发给客户端（见各变体 {@code network/VoicePortSync}），客户端据此在本机为这些端口开监听
 * （tunnel 打标隧道 / bridge 直连真实服务器同端口）。两端 channelId 顺序必须一致。</p>
 *
 * <p>独立顶层 public 类（而非内嵌于包级私有的 {@code ServerProxyRuntime}），以便 {@code network} 包引用。</p>
 */
public record VoicePortPlan(String transport, List<Integer> ports) {
    public VoicePortPlan {
        transport = transport == null ? "off" : transport;
        ports = ports == null ? List.of() : List.copyOf(ports);
    }

    public static VoicePortPlan empty() {
        return new VoicePortPlan("off", List.of());
    }

    /** tunnel 模式且确有语音端口：game forwarder 需要按 channelId 多路复用入口端口。 */
    public boolean isTunnel() {
        return "tunnel".equals(transport) && !ports.isEmpty();
    }

    /** bridge 模式且确有语音端口：客户端直连真实服务器同端口，服务端不额外中转。 */
    public boolean isBridge() {
        return "bridge".equals(transport) && !ports.isEmpty();
    }
}
