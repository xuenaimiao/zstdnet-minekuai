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

package cn.tohsaka.factory.zstdnet.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 包装 Velocity 的子通道初始化器（与 ViaVersion 的 Velocity 平台同一做法）：先原样执行被包装者的
 * {@code initChannel}（Velocity 自己的，或其它插件已经包过的一层），再把我们的一次性处理器 {@code addFirst}
 * 到管线最前。
 *
 * <p>与其它同样包装初始化器的插件共存：无论我们在里层还是外层，被包装者都完整执行、我们只 addFirst，
 * 所以 {@link VelocityHandshakeSniffer} 最终总在最前（其它插件一般以 addBefore/addAfter 按名字定位，不会挤到它前面）。</p>
 */
@ChannelHandler.Sharable
final class VelocityChannelInitializer extends ChannelInitializer<Channel> {

    private final ChannelInitializer<Channel> original;
    private final Method originalInitChannel;
    private final Supplier<ChannelHandler> handlerFactory;
    private final BooleanSupplier enabled;

    private VelocityChannelInitializer(ChannelInitializer<Channel> original, Method originalInitChannel,
                                       Supplier<ChannelHandler> handlerFactory, BooleanSupplier enabled) {
        this.original = original;
        this.originalInitChannel = originalInitChannel;
        this.handlerFactory = handlerFactory;
        this.enabled = enabled;
    }

    /**
     * @param original       被包装的初始化器
     * @param handlerFactory 每条新连接调用一次，产出要 addFirst 的处理器
     * @param enabled        关闭（插件停用）后只执行被包装者，不再挂处理器
     * @throws ReflectiveOperationException 拿不到 {@code ChannelInitializer#initChannel(Channel)}（被模块系统挡下等）
     */
    static VelocityChannelInitializer wrap(ChannelInitializer<Channel> original, Supplier<ChannelHandler> handlerFactory,
                                           BooleanSupplier enabled) throws ReflectiveOperationException {
        if (original == null) {
            throw new IllegalArgumentException("original initializer is null");
        }
        // initChannel 是 protected 的；按 ChannelInitializer 上的声明取、对 original 调用，会虚分派到它的实现。
        Method method = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        method.setAccessible(true);
        return new VelocityChannelInitializer(original, method, handlerFactory, enabled);
    }

    ChannelInitializer<Channel> original() {
        return original;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        try {
            originalInitChannel.invoke(original, channel);
        } catch (InvocationTargetException e) {
            // 被包装者自己抛的异常原样抛出，保持 Velocity 原有的失败语义（ChannelInitializer 会关闭该连接）。
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }

        if (!enabled.getAsBoolean()) {
            return;
        }
        try {
            if (channel.pipeline().get(VelocityHandshakeSniffer.NAME) == null) {
                channel.pipeline().addFirst(VelocityHandshakeSniffer.NAME, handlerFactory.get());
            }
        } catch (Throwable t) {
            VelocityPipelineInjector.warnOnce("hook-child", "failed to hook an incoming connection: " + t);
        }
    }
}
