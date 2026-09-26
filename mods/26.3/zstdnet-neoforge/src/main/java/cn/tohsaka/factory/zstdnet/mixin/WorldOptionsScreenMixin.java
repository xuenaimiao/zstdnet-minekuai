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
import cn.tohsaka.factory.zstdnet.client.WorldOptionsLanAccess;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3「世界选项」界面里的 Zstd 端口。
 *
 * <p>26.2 把「开放到局域网」并进了 MultiplayerOptionsScreen，26.3 又把它和旧 options/WorldOptionsScreen 合并成
 * screens/WorldOptionsScreen：多人选项是 ScrollableLayout 里的一个两列网格（局域网开关 | 端口框），
 * 页脚一个「应用更改」按钮统一提交所有世界设置。旧做法（按 {@code lanServer.start} 文字找「开始」按钮、
 * 按坐标摆输入框、在 Screen 头部截鼠标/ENTER）在这里全部落空，所以改为挂在原版自己的流程上：</p>
 * <ul>
 *   <li>{@code multiplayerOptions}：原版端口框加进网格后，紧接着在下一行补「Zstd 端口」标签 + 输入框——
 *       控件进的是原版布局，滚动/缩放/焦点/渲染全由原版负责；</li>
 *   <li>{@code updatePortControlsState} 末尾：随原版端口框一起启用/禁用；</li>
 *   <li>{@code hasChanges} 返回前：Zstd 端口无效时「应用更改」保持禁用，与原版端口无效的处理一致；</li>
 *   <li>{@code publish}（LAN）开头：把 Zstd 端口与原版最终选定的游戏端口（{@code this.port}，端口框留空时由原版自动选）
 *       写进 zstdnet-server.properties，随后服务端 tick 按配置拉起局域网 zstd 代理。</li>
 * </ul>
 * 以上注入点均已对照 26.3 反编译源码核对：{@code multiplayerOptions} 方法体内只直接调用一次 {@code updatePortControlsState()}，
 * 且只有一个 {@code GridLayout.RowHelper} 局部变量。
 */
@Mixin(WorldOptionsScreen.class)
abstract class WorldOptionsScreenMixin extends Screen implements WorldOptionsLanAccess {
    @Shadow
    private MinecraftServer.MultiplayerScope wantedMultiplayerScope;

    @Shadow
    private int port;

    protected WorldOptionsScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    private void updateApplyChangesActiveState() {
        throw new AssertionError();
    }

    @Inject(
        method = "multiplayerOptions",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/WorldOptionsScreen;updatePortControlsState()V")
    )
    private void zstdnet$addZstdPortRow(
        LinearLayout content,
        IntegratedServer singleplayerServer,
        CallbackInfo ci,
        @Local GridLayout.RowHelper helper
    ) {
        ClientProxyPublisher.onWorldOptionsMultiplayer((WorldOptionsScreen) (Object) this, helper, this.zstdnet$lanWanted());
    }

    @Inject(method = "updatePortControlsState", at = @At("TAIL"))
    private void zstdnet$syncZstdPortControls(CallbackInfo ci) {
        ClientProxyPublisher.onWorldOptionsPortControls((WorldOptionsScreen) (Object) this, this.zstdnet$lanWanted());
    }

    @Inject(method = "hasChanges", at = @At("RETURN"), cancellable = true)
    private void zstdnet$gateApplyOnZstdPort(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
            && this.zstdnet$lanWanted()
            && !ClientProxyPublisher.isWorldOptionsZstdPortValid((WorldOptionsScreen) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "publish", at = @At("HEAD"))
    private void zstdnet$writeLanPorts(IntegratedServer singleplayerServer, MinecraftServer.MultiplayerScope scope, CallbackInfo ci) {
        if (scope == MinecraftServer.MultiplayerScope.LAN) {
            ClientProxyPublisher.onWorldOptionsPublish((WorldOptionsScreen) (Object) this, this.port);
        }
    }

    @Override
    public int zstdnet$lanPort() {
        return this.port;
    }

    @Override
    public void zstdnet$refreshApplyChanges() {
        this.updateApplyChangesActiveState();
    }

    private boolean zstdnet$lanWanted() {
        return this.wantedMultiplayerScope == MinecraftServer.MultiplayerScope.LAN;
    }
}
