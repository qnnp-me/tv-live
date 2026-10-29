package me.qnnp.tvlive.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机页接口用的极简 JSON：解析请求体（对象/数组/字符串/数字/布尔）与转义输出。
 *
 * <p>不引第三方；只覆盖本包用到的结构。解析错误消息面向开发/用户（中文）。</p>
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text == null ? "" : text;
    }

    /** 解析 JSON 对象；不是对象或格式错误抛 {@link JsonException}。 */
    static Map<String, Object> parseObject(String text) throws JsonException {
        Json json = new Json(text);
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.pos < json.text.length()) {
            throw new JsonException("多余内容");
        }
        if (!(value instanceof Map)) {
            throw new JsonException("请求应为 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /** 字符串字段；缺失/类型不符返回 null。 */
    static String string(Map<String, Object> object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** 布尔字段；缺失/类型不符返回 null。 */
    static Boolean bool(Map<String, Object> object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.get(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /** 数字字段；缺失/类型不符返回 null。 */
    static Long number(Map<String, Object> object, String key) {
        if (object == null) {
            return null;
        }
        Object value = object.get(key);
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
    }

    /** JSON 字符串字面量（null → null）。 */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }

    // ------------------------------------------------------------------ 解析

    private Object readValue() throws JsonException {
        skipWhitespace();
        if (pos >= text.length()) {
            throw new JsonException("内容意外结束");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw new JsonException("无法识别的字符: " + c);
        }
    }

    private Map<String, Object> readObject() throws JsonException {
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonException("对象键必须是字符串");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonException("缺少冒号");
            }
            pos++;
            object.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return object;
            }
            throw new JsonException("对象缺少逗号或右括号");
        }
    }

    private List<Object> readArray() throws JsonException {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new JsonException("数组缺少逗号或右括号");
        }
    }

    private String readString() throws JsonException {
        pos++; // "
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw new JsonException("字符串转义不完整");
            }
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw new JsonException("\\u 转义不完整");
                    }
                    String hex = text.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw new JsonException("\\u 转义无效");
                    }
                    break;
                default:
                    throw new JsonException("未知转义: \\" + escaped);
            }
        }
        throw new JsonException("字符串未闭合");
    }

    private Number readNumber() throws JsonException {
        int start = pos;
        boolean decimal = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                decimal = true;
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        try {
            return decimal ? (Number) Double.valueOf(token) : (Number) Long.valueOf(token);
        } catch (NumberFormatException e) {
            throw new JsonException("数字无效: " + token);
        }
    }

    private void expect(String literal) throws JsonException {
        if (!text.startsWith(literal, pos)) {
            throw new JsonException("应为 " + literal);
        }
        pos += literal.length();
    }

    private char peek() throws JsonException {
        if (pos >= text.length()) {
            throw new JsonException("内容意外结束");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    /** 请求体不是合法 JSON。 */
    static final class JsonException extends Exception {

        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }
}
