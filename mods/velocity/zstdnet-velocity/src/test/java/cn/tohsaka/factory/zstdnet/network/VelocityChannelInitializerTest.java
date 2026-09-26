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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link VelocityChannelInitializer}：包装后被包装者完整执行、我方处理器位于最前，并能与其它同样包装初始化器的插件叠加。
 */
class VelocityChannelInitializerTest {

    /** 形状同 Velocity 的 ServerChannelInitializer（开了 haproxy-protocol 时会无名 addFirst 一个 HAProxyMessageDecoder）。 */
    private static final class FakeVelocityInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel channel) {
            channel.pipeline()
                .addLast("legacy-ping-decoder", new ChannelInboundHandlerAdapter())
                .addLast("frame-decoder", new ChannelInboundHandlerAdapter())
                .addLast("minecraft-decoder", new ChannelInboundHandlerAdapter())
                .addLast("handler", new ChannelInboundHandlerAdapter());
            channel.pipeline().addFirst(new HAProxyMessageDecoder());
        }
    }

    /** 模拟 ViaVersion 的做法：反射调用被包装者的 initChannel，再按名字 addBefore 自己的处理器。 */
    @ChannelHandler.Sharable
    private static final class OtherPluginInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> inner;

        OtherPluginInitializer(ChannelInitializer<Channel> inner) {
            this.inner = inner;
        }

        @Override
        protected void initChannel(Channel channel) throws Exception {
            Method method = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
            method.setAccessible(true);
            method.invoke(inner, channel);
            channel.pipeline().addBefore("minecraft-decoder", "via-decoder", new ChannelInboundHandlerAdapter());
        }
    }

    private static ChannelHandler marker() {
        return new ChannelInboundHandlerAdapter();
    }

    private static List<String> names(EmbeddedChannel channel) {
        List<String> names = channel.pipeline().names();
        return names.subList(0, names.size() - 1); // 去掉 EmbeddedChannel 自带的尾部处理器
    }

    @Test
    void runsTheOriginalThenPutsOurHandlerFirst() throws Exception {
        VelocityChannelInitializer wrapper = VelocityChannelInitializer.wrap(new FakeVelocityInitializer(), VelocityChannelInitializerTest::marker, () -> true);

        EmbeddedChannel channel = new EmbeddedChannel(wrapper);

        List<String> names = names(channel);
        assertEquals(VelocityHandshakeSniffer.NAME, names.get(0));
        assertInstanceOf(HAProxyMessageDecoder.class, channel.pipeline().get(names.get(1)));
        assertEquals(List.of("legacy-ping-decoder", "frame-decoder", "minecraft-decoder", "handler"), names.subList(2, names.size()));
        channel.finishAndReleaseAll();
    }

    @Test
    void staysFirstWhenAnotherPluginWrapsUs() throws Exception {
        VelocityChannelInitializer ours = VelocityChannelInitializer.wrap(new FakeVelocityInitializer(), VelocityChannelInitializerTest::marker, () -> true);

        EmbeddedChannel channel = new EmbeddedChannel(new OtherPluginInitializer(ours));

        List<String> names = names(channel);
        assertEquals(VelocityHandshakeSniffer.NAME, names.get(0));
        assertEquals(names.indexOf("minecraft-decoder") - 1, names.indexOf("via-decoder"));
        channel.finishAndReleaseAll();
    }

    @Test
    void staysFirstWhenWeWrapAnotherPlugin() throws Exception {
        ChannelInitializer<Channel> via = new OtherPluginInitializer(new FakeVelocityInitializer());
        VelocityChannelInitializer ours = VelocityChannelInitializer.wrap(via, VelocityChannelInitializerTest::marker, () -> true);

        EmbeddedChannel channel = new EmbeddedChannel(ours);

        List<String> names = names(channel);
        assertEquals(VelocityHandshakeSniffer.NAME, names.get(0));
        assertEquals(names.indexOf("minecraft-decoder") - 1, names.indexOf("via-decoder"));
        assertSame(via, ours.original());
        channel.finishAndReleaseAll();
    }

    @Test
    void addsOnlyOneHandlerEvenIfWrappedTwice() throws Exception {
        VelocityChannelInitializer inner = VelocityChannelInitializer.wrap(new FakeVelocityInitializer(), VelocityChannelInitializerTest::marker, () -> true);
        VelocityChannelInitializer outer = VelocityChannelInitializer.wrap(inner, VelocityChannelInitializerTest::marker, () -> true);

        EmbeddedChannel channel = new EmbeddedChannel(outer);

        long count = names(channel).stream().filter(VelocityHandshakeSniffer.NAME::equals).count();
        assertEquals(1, count);
        channel.finishAndReleaseAll();
    }

    @Test
    void onlyRunsTheOriginalOnceDisabled() throws Exception {
        VelocityChannelInitializer wrapper = VelocityChannelInitializer.wrap(new FakeVelocityInitializer(), VelocityChannelInitializerTest::marker, () -> false);

        EmbeddedChannel channel = new EmbeddedChannel(wrapper);

        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
        assertEquals(5, names(channel).size());
        channel.finishAndReleaseAll();
    }

    @Test
    void propagatesFailuresOfTheOriginal() throws Exception {
        ChannelInitializer<Channel> broken = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                throw new IllegalStateException("boom");
            }
        };
        VelocityChannelInitializer wrapper = VelocityChannelInitializer.wrap(broken, VelocityChannelInitializerTest::marker, () -> true);

        EmbeddedChannel channel = new EmbeddedChannel(wrapper);

        // ChannelInitializer 的默认失败语义：关闭该连接；我们不吞异常、也不再挂处理器。
        assertFalse(channel.isOpen());
        assertNull(channel.pipeline().get(VelocityHandshakeSniffer.NAME));
    }
}
