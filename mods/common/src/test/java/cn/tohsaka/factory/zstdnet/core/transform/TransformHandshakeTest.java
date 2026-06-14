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

package cn.tohsaka.factory.zstdnet.core.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 变换能力握手标记的解析/剥离：与 FML、real-ip 等其它 \0 字段任意顺序共存，剥离后其余字段原样保留。
 */
class TransformHandshakeTest {

    @Test
    void parsesVersionFromVariousHosts() {
        assertEquals(0, TransformHandshake.parseVersion("play.example.com"));
        assertEquals(0, TransformHandshake.parseVersion(null));
        assertEquals(1, TransformHandshake.parseVersion("play.example.com\0zstdnet-xform=1"));
        assertEquals(3, TransformHandshake.parseVersion("play.example.com\0FML2\0zstdnet-xform=3"));
        assertEquals(2, TransformHandshake.parseVersion("h\0zstdnet-xform=2\0FML2\0"));
        assertEquals(1, TransformHandshake.parseVersion("h\0zstdnet-real-ip=YWJj\0zstdnet-xform=1"));
        // 非法版本值 → 0
        assertEquals(0, TransformHandshake.parseVersion("h\0zstdnet-xform=abc"));
    }

    @Test
    void stripsMarkerKeepingOtherFields() {
        assertEquals("play.example.com", TransformHandshake.strip("play.example.com"));
        assertEquals("play.example.com", TransformHandshake.strip("play.example.com\0zstdnet-xform=1"));
        assertEquals("play.example.com\0FML2", TransformHandshake.strip("play.example.com\0FML2\0zstdnet-xform=3"));
        // 标记在中间：剥离后前后字段拼接（含前导 \0 一并移除）
        assertEquals("h\0FML2", TransformHandshake.strip("h\0zstdnet-xform=2\0FML2"));
        assertEquals("h\0zstdnet-real-ip=YWJj", TransformHandshake.strip("h\0zstdnet-real-ip=YWJj\0zstdnet-xform=1"));
        assertEquals(null, TransformHandshake.strip(null));
    }

    @Test
    void advertiseRoundTrip() {
        String host = "play.example.com";
        for (int v = 1; v <= 3; v++) {
            String advertised = host + TransformHandshake.advertiseSuffix(v);
            assertEquals(v, TransformHandshake.parseVersion(advertised));
            assertEquals(host, TransformHandshake.strip(advertised));
        }
        // 与已有 FML 后缀叠加
        String withFml = host + "\0FML2\0";
        String advertised = withFml + TransformHandshake.advertiseSuffix(2);
        assertEquals(2, TransformHandshake.parseVersion(advertised));
        assertEquals(withFml, TransformHandshake.strip(advertised));
    }
}
