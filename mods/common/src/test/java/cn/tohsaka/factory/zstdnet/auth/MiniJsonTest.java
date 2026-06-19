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
        Object root = MiniJson.parse("""
            {
              "id": "abc",
              "name": "Notch",
              "properties": [ {"name":"textures","value":"v","signature":"s"} ],
              "n": 3, "b": true, "z": null
            }
            """);
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
}
