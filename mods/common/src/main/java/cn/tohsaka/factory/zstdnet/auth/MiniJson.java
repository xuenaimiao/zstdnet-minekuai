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

package cn.tohsaka.factory.zstdnet.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简、零依赖的 JSON 解析器（递归下降），仅供 {@link MojangPremiumVerifier} 解析 {@code hasJoined} 响应使用。
 * <p>
 * 不引入 Gson 等外部库——MC 各变体/测试 classpath 对第三方库的可见性不一致（NeoForge 单测 classpath 即无 Gson），
 * 自带解析器可在全部变体与单测下稳定工作。解析为 {@link Map}（对象）/ {@link List}（数组）/ {@link String} /
 * {@link Double} / {@link Boolean} / {@code null}。解析失败抛 {@link IllegalArgumentException}。
 */
final class MiniJson {

    /**
     * 递归嵌套深度上限。防止恶意/异常的会话服（{@code premium_session_server} 可配为第三方
     * authlib-injector / Yggdrasil）返回深嵌套 JSON 触发 {@link StackOverflowError}——
     * 超限时抛 {@link IllegalArgumentException}（属 {@code RuntimeException}，会被
     * {@link MojangPremiumVerifier#parse} 安全归一为「未通过=null」）。{@code hasJoined} 正常响应仅 ~3 层，64 足够。
     */
    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** 解析一段 JSON 文本为对象树。 */
    static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("null json");
        }
        MiniJson parser = new MiniJson(json);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.pos != parser.src.length()) {
            throw new IllegalArgumentException("trailing content at " + parser.pos);
        }
        return value;
    }

    private Object readValue(int depth) {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of json");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
            case 'f':
                return readBoolean();
            case 'n':
                return readNull();
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("json nested too deep at " + pos);
        }
        Map<String, Object> obj = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            obj.put(key, readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return obj;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
            }
        }
    }

    private List<Object> readArray(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("json nested too deep at " + pos);
        }
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u': {
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("bad escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Boolean readBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("invalid literal at " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("invalid literal at " + pos);
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("invalid value at " + start);
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number at " + start);
        }
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of json");
        }
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of json");
        }
        return src.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + (pos - 1));
        }
    }
}
