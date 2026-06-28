package cn.tohsaka.factory.zstdnet.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesNestedObjectWithArrayAndWhitespace() {
        Object root = MiniJson.parse(
            "{\n"
            + "  \"id\": \"abc\",\n"
            + "  \"name\": \"Notch\",\n"
            + "  \"properties\": [ {\"name\":\"textures\",\"value\":\"v\",\"signature\":\"s\"} ],\n"
            + "  \"n\": 3, \"b\": true, \"z\": null\n"
            + "}\n");
        Map<String, Object> obj = (Map<String, Object>) root;
        assertEquals("abc", obj.get("id"));
        assertEquals("Notch", obj.get("name"));
        assertEquals(Boolean.TRUE, obj.get("b"));
        assertNull(obj.get("z"));
        assertTrue(obj.get("z") == null);
        List<Object> props = (List<Object>) obj.get("properties");
        assertEquals(1, props.size());
        Map<String, Object> p = (Map<String, Object>) props.get(0);
        assertEquals("textures", p.get("name"));
        assertEquals("s", p.get("signature"));
    }

    @Test
    void handlesStringEscapes() {
        Object root = MiniJson.parse("{\"k\":\"a\\\"b\\\\c\\n\\u0041\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) root;
        assertEquals("a\"b\\c\nA", obj.get("k"));
    }

    @Test
    void emptyContainers() {
        assertTrue(((Map<?, ?>) MiniJson.parse("{}")).isEmpty());
        assertTrue(((List<?>) MiniJson.parse("[]")).isEmpty());
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{} junk"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(null));
    }

    @Test
    void rejectsDeeplyNestedInsteadOfStackOverflow() {
        // 深嵌套（恶意/异常的第三方会话服响应）必须以 IllegalArgumentException 拒绝，
        // 而非 StackOverflowError 逃逸——超深数组与对象两种递归路径都要覆盖。
        String deepArray = repeat("[", 5000) + repeat("]", 5000);
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(deepArray));
        StringBuilder deepObject = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            deepObject.append("{\"a\":");
        }
        deepObject.append("1").append(repeat("}", 5000));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(deepObject.toString()));
    }

    @Test
    void acceptsNestingWithinLimit() {
        // hasJoined 真实响应仅 ~3 层；限内的合理嵌套仍须正常解析。
        Object root = MiniJson.parse(repeat("[", 32) + "1" + repeat("]", 32));
        assertTrue(root instanceof List);
    }

    /** Java 8 兼容：替代 String.repeat（Java 11）。 */
    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
