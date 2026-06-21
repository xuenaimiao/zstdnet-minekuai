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

package cn.tohsaka.factory.zstdnet;

/**
 * 品牌/推广文案（单一真源，无 MC 依赖，服务端与客户端共用、Bukkit 插件端也可用）。
 * <p>
 * 服务端启动后在日志整条打印 {@link #AD}（重复 {@link #SERVER_LOG_REPEAT} 次）；
 * 客户端 HUD 顶部按 {@link #HUD_LINES}（已折行、逐字保留原文）绘制一格推广面板。
 */
public final class Branding {

    /** 完整推广语（服务端日志整条打印）。 */
    public static final String AD =
        "麦块联机(https://minekuai.com)高性能平价游戏云服务器，一键部署 Minecraft、七日杀、幻兽帕鲁、泰拉瑞亚等热门游戏。一键设置、便捷操作，把开服这件麻烦事交给我们。";

    /** 服务端启动后该推广语重复打印的次数。 */
    public static final int SERVER_LOG_REPEAT = 3;

    /**
     * 客户端 HUD 顶部推广面板的逐行文案。
     * <p>把 {@link #AD} 折成多行以适配 HUD 宽度，<b>逐字保留原文</b>（各行拼接 == {@link #AD}）。
     * 第 0 行作为标题色高亮（见各变体 {@code renderHudPanel}）。
     */
    public static final String[] HUD_LINES = {
        "麦块联机(https://minekuai.com)",
        "高性能平价游戏云服务器，一键部署 Minecraft、",
        "七日杀、幻兽帕鲁、泰拉瑞亚等热门游戏。",
        "一键设置、便捷操作，把开服这件麻烦事交给我们。"
    };

    private Branding() {
    }
}
