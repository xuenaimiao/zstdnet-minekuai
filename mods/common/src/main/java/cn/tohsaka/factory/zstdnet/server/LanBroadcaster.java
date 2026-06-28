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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * 多网卡 LAN 广播补发器：解决「开放到局域网后别的客户端在列表里搜不到」。
 *
 * <p>原版 {@code LanServerPinger} 只用一个 {@code DatagramSocket} 把
 * {@code [MOTD]..[/MOTD][AD]port[/AD]} 发到组播组 {@code 224.0.2.60:4445}，出网卡由系统/JVM 选默认。
 * 探测端 {@code LanServerDetection} 也只在默认网卡 join 组播组。当机器有多块网卡（蓝牙 PAN、WSL/虚拟网卡、
 * 169.254 链路本地等），收发两端常落在不同网卡上 → 组播包互相收不到 → 列表里看不到（手动按 IP:端口 直连仍可）。</p>
 *
 * <p>本类在「开放到局域网」期间额外起一个守护线程，把<strong>与原版完全一致</strong>的 ping 文本同时往
 * <strong>每一块在用网卡（含环回）</strong>发一遍。无论对端探测端落在哪块网卡都能收到；payload 与原版逐字一致，
 * 客户端按 {@code ip:port} 去重，不会出现重复条目。纯 Java 网络，不依赖任何 MC API，由各变体的
 * {@code ServerProxyBootstrap} 传入 motd/port 调用。失败不致命（原版自带 pinger 仍在跑）。</p>
 */
public final class LanBroadcaster {
    private static final Logger LOGGER = LoggerFactory.getLogger(LanBroadcaster.class);
    private static final String MULTICAST_GROUP = "224.0.2.60";
    private static final int MULTICAST_PORT = 4445;
    private static final long INTERVAL_MS = 1500L;

    private static final Object LOCK = new Object();
    private static Thread thread;
    private static volatile boolean running;
    private static volatile String payload = "";
    private static volatile int announcedPort = -1;

    private LanBroadcaster() {
    }

    /**
     * 构造原版 LAN ping 文本（与 {@code net.minecraft.client.server.LanServerPinger} 一致），
     * 便于客户端按 {@code ip:port} 去重。motd 里若含 {@code [MOTD]/[AD]} 标记或换行会被清洗。
     */
    public static String pingPayload(String motd, int port) {
        return "[MOTD]" + sanitizeMotd(motd) + "[/MOTD][AD]" + port + "[/AD]";
    }

    /**
     * 启动（或热更新）多网卡 LAN 广播。motd/port 变化会即时更新 payload；已在运行则只更新、不重起线程。
     */
    public static void start(String motd, int port) {
        synchronized (LOCK) {
            payload = pingPayload(motd, port);
            announcedPort = port;
            if (running) {
                return;
            }
            running = true;
            Thread t = new Thread(LanBroadcaster::loop, "zstdnet-lan-broadcaster");
            t.setDaemon(true);
            thread = t;
            t.start();
            LOGGER.info("[zstdnet-server] multi-NIC LAN broadcast started for game port {} (helps other clients see it in the LAN list).", port);
        }
    }

    /** 停止广播。未在运行时为廉价空操作（可每 tick 安全调用）。 */
    public static void stop() {
        synchronized (LOCK) {
            if (thread == null) {
                return;
            }
            running = false;
            announcedPort = -1;
            thread.interrupt();
            thread = null;
        }
    }

    public static boolean isRunning() {
        return running;
    }

    /** 当前广播的游戏端口；未运行时为 -1。 */
    public static int announcedPort() {
        return announcedPort;
    }

    private static void loop() {
        InetAddress group;
        try {
            group = InetAddress.getByName(MULTICAST_GROUP);
        } catch (Exception e) {
            running = false;
            return;
        }
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(1); // 同子网/本机足够；与原版 LAN ping 范围一致。
            while (running) {
                broadcastOnAllInterfaces(socket, group, payload.getBytes(StandardCharsets.UTF_8));
                Thread.sleep(INTERVAL_MS);
            }
        } catch (InterruptedException ie) {
            // stop() 触发的正常退出。
        } catch (Exception e) {
            LOGGER.debug("[zstdnet-server] multi-NIC LAN broadcast stopped: {}", e.toString());
        } finally {
            running = false;
        }
    }

    private static void broadcastOnAllInterfaces(MulticastSocket socket, InetAddress group, byte[] data) {
        Enumeration<NetworkInterface> ifaces;
        try {
            ifaces = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            return;
        }
        DatagramPacket packet = new DatagramPacket(data, data.length, group, MULTICAST_PORT);
        while (ifaces.hasMoreElements()) {
            NetworkInterface nif = ifaces.nextElement();
            try {
                if (!nif.isUp() || (!nif.isLoopback() && !nif.supportsMulticast())) {
                    continue;
                }
                socket.setNetworkInterface(nif);
                socket.send(packet);
            } catch (Exception ignored) {
                // 单块网卡发送失败就跳过，继续下一块。
            }
        }
    }

    private static String sanitizeMotd(String motd) {
        if (motd == null || motd.trim().isEmpty()) {
            return "Minecraft";
        }
        return motd
            .replace("[MOTD]", "").replace("[/MOTD]", "")
            .replace("[AD]", "").replace("[/AD]", "")
            .replace("\n", " ").replace("\r", " ");
    }
}
