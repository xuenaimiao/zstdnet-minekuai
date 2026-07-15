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

import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ContainerEventHandler.class)
interface ContainerEventHandlerMixin {
    // 26.1 起 mouseClicked(DDI)Z 改为 mouseClicked(MouseButtonEvent, boolean)。坐标/按键从事件对象读取。
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true)
    private void zstdnet$onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Screen screen)) {
            return;
        }

        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        boolean handled =
            (screen instanceof JoinMultiplayerScreen joinScreen && ClientProxyPublisher.onJoinMultiplayerMouseClicked(joinScreen, mouseX, mouseY, button))
                || (screen instanceof DirectJoinServerScreen directJoinScreen && ClientProxyPublisher.onDirectJoinMouseClicked(directJoinScreen, mouseX, mouseY, button))
                || (screen instanceof MultiplayerOptionsScreen shareToLanScreen && ClientProxyPublisher.onShareToLanMouseClicked(shareToLanScreen, mouseX, mouseY, button));
        if (handled) {
            cir.setReturnValue(true);
        }
    }
}
