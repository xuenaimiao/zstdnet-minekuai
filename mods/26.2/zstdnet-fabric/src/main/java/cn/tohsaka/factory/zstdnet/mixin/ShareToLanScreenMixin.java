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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2 删除了 ShareToLanScreen（「开放到局域网」界面），其功能并入 MultiplayerOptionsScreen
// （同一块界面改名：仍持有 lanServer.port 端口框 + lanServer.start 按钮，仍调用 IntegratedServer.publishServer）。
@Mixin(MultiplayerOptionsScreen.class)
abstract class ShareToLanScreenMixin extends Screen {
    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void zstdnet$afterInit(CallbackInfo ci) {
        EditBox widget = ClientProxyPublisher.onShareToLanInit((MultiplayerOptionsScreen) (Object) this);
        if (widget != null) {
            this.addRenderableWidget(widget);
        }
    }

    // 26.2 的 MultiplayerOptionsScreen 不覆写 extractRenderState（继承自 Screen），只覆写 extractBackground(...)，
    // 故渲染注入点改到 extractBackground；参数签名一致（GuiGraphicsExtractor,int,int,float）。
    @Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"))
    private void zstdnet$beforeRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ClientProxyPublisher.onShareToLanBeforeRender((MultiplayerOptionsScreen) (Object) this);
    }

    @Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void zstdnet$afterRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ClientProxyPublisher.onShareToLanRender((MultiplayerOptionsScreen) (Object) this, guiGraphics);
    }
}
