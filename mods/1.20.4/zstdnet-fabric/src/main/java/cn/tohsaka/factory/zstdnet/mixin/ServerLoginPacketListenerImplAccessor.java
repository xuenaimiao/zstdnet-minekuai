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

package cn.tohsaka.factory.zstdnet.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@code ServerLoginPacketListenerImpl} 的登录档案与连接，用于「登录阶段正版验证」：
 * 核验通过后把离线档案替换为真实正版档案（在 {@code verifyLoginAndFinishConnectionSetup} 读取它之前完成）。
 * <p>
 * MC 1.20.2 重写了登录流程：原来的单一 {@code gameProfile} 字段被拆成
 * {@code requestedUsername}（客户端 hello 里报的名字，早在 {@code handleHello} 就有值）和
 * {@code authenticatedProfile}（{@code startClientVerification} 落定、{@code tick()} 里
 * 交给 {@code verifyLoginAndFinishConnectionSetup} 的最终档案）。故取名字读前者、换档写后者。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccessor {

    @Accessor("requestedUsername")
    String zstdnet$getRequestedUsername();

    @Accessor("authenticatedProfile")
    GameProfile zstdnet$getGameProfile();

    @Accessor("authenticatedProfile")
    void zstdnet$setGameProfile(GameProfile profile);

    @Accessor("connection")
    Connection zstdnet$getConnection();
}
