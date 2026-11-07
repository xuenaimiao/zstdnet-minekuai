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

package cn.tohsaka.factory.zstdnet.core.transform;

/**
 * 下行编码层（区块引用缓存 CRC / 实体变换）的「入口闸」：第一次切帧之前，先看下行流的首帧，判断这条流
 * 往后还是不是明文的 {@code [VarInt 长度][载荷]} MC 帧流。
 *
 * <h3>为什么需要</h3>
 * 编码层用 {@link PacketFramer} 把后端字节切成完整帧再编码，<b>不足一帧的尾巴会一直留在缓冲里</b>，直到后面的字节把它补齐。
 * 对明文 MC 流这只是毫秒级的暂存；但流一旦变成 AES 密文，「长度前缀」就是随机数，密文被当成一个永远收不齐的大帧——
 * 服务端明明已经发了数据，客户端却什么也收不到，双方互等到超时。
 *
 * <p>ZstdNet 自己的登录路径始终是明文（后端强制 offline-mode，正版验证走带外），所以平时碰不到。会碰到的是
 * 同一个端口上另有「自带一遍原版加密握手」的协议：
 * <ul>
 *   <li>AutoModpack 5.x 的 HOLEPUNCH：客户端用 Login Start 的 UUID 暗号让服务端在原版建登录监听器之前接管连接，
 *       随后走加密请求/响应，再在 AES 之上跑 TLS 下载整合包；</li>
 *   <li>{@code online-mode=true} 且未启用内置正版验证（{@code premium_verification=off}）的后端：原版加密生效。</li>
 * </ul>
 *
 * <h3>判据</h3>
 * 登录态下服务端发出的<b>第一个</b>包若是 Encryption Request（packetId {@code 0x01}），客户端回应之后整条流就是密文。
 * 离线登录的首帧只可能是 Disconnect(0x00) / Set Compression(0x03) / Login Plugin Request(0x04)（ZstdNet 的正版门控查询）等，
 * 不会是 0x01，所以不会误伤正常连接。首帧的前缀若连合法 VarInt 都不是，同样说明这不是 MC 帧流。
 *
 * <p>判为 {@link Verdict#UNFRAMED} 后，编码层对这条连接永久退化成原样透传：不写前导、不切帧、不缓冲。客户端的解码端
 * 本来就是「流首没有前导 MAGIC → 原样透传」，所以无需改协议、新旧客户端都兼容。
 */
public final class FirstFrameGate {
    /** 登录态服务端→客户端的 Encryption Request 包 id（自 1.7 起未变）。 */
    public static final int LOGIN_ENCRYPTION_REQUEST_ID = 0x01;

    /** 闸门判定结果。 */
    public enum Verdict {
        /** 首帧还没收够字节，暂不能判定（至多暂存几个字节）。 */
        UNDECIDED,
        /** 照常切帧：这是明文 MC 帧流。 */
        FRAMED,
        /** 不再切帧：此后的字节是密文或根本不是 MC 帧流，必须原样透传、不得缓冲。 */
        UNFRAMED
    }

    private FirstFrameGate() {
    }

    /** 根据 {@code framer} 里已缓冲的首帧前缀判定。不消费、不推进读指针。 */
    public static Verdict decide(PacketFramer framer) {
        int packetId = framer.peekFirstPacketId();
        if (packetId == PacketFramer.FIRST_NEED_MORE) {
            return Verdict.UNDECIDED;
        }
        if (packetId == PacketFramer.FIRST_NOT_A_FRAME || packetId == LOGIN_ENCRYPTION_REQUEST_ID) {
            return Verdict.UNFRAMED;
        }
        // 含 FIRST_EMPTY_FRAME：合法的空帧，没有 packetId 可言，自然也不是加密请求——照常切帧。
        return Verdict.FRAMED;
    }
}
