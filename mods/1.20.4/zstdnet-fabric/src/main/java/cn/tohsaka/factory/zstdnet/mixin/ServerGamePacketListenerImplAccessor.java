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

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 取 play 阶段监听器底层的 {@code Connection}。
 * <p>
 * MC 1.20.2 引入配置（configuration）阶段后，{@code connection} 字段从
 * {@code ServerGamePacketListenerImpl} 上移到了新的公共父类 {@code ServerCommonPacketListenerImpl}
 * （play/configuration 两个监听器共用），故这里必须以父类为 mixin 目标——否则
 * {@code @Accessor} 在 APPLY 阶段找不到字段而崩溃。调用方仍旧把
 * {@code player.connection}（{@code ServerGamePacketListenerImpl}）强转成本接口：
 * 子类继承父类被织入的接口，运行时转换照样成立。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
    @Accessor("connection")
    Connection zstdnet$getConnection();
}
