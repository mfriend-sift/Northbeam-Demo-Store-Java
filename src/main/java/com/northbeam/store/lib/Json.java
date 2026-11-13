package com.northbeam.store.lib;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON.parse and JSON.stringify as the shared contract is written in, so a request body means what the contract says.
 * The parser is iterative (a 100 KB body of nested brackets must not overflow the stack), takes the last of two
 * equal keys, accepts lone surrogate escapes, and reads numbers as doubles (1e400 is Infinity, as in JavaScript).
 */
public final class Json {
    private Json() {}

    /** Thrown for text that is not JSON. */
    public static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public FormatException() { super("Invalid JSON"); }
    }

    public static Object parse(String s) { return new Reader(s).run(); }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String text) { s = text; }

        private FormatException bad() { return new FormatException(); }

        private void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
        }

        private char peek() {
            if (i >= s.length()) throw bad();
            return s.charAt(i);
        }

        Object run() {
            Deque<Object> stack = new ArrayDeque<>();
            Deque<String> keys = new ArrayDeque<>();
            Object value;
            while (true) {
                // Read the start of a value.
                ws();
                char c = peek();
                boolean done;
                value = null;
                if (c == '{') {
                    i++;
                    ws();
                    if (peek() == '}') { i++; value = new JsObject(); done = true; }
                    else { stack.push(new JsObject()); keys.push(key()); done = false; }
                } else if (c == '[') {
                    i++;
                    ws();
                    if (peek() == ']') { i++; value = new ArrayList<Object>(); done = true; }
                    else { stack.push(new ArrayList<Object>()); done = false; }
                } else { value = scalar(); done = true; }
                if (!done) continue;

                // A value is complete: attach it to its container, or finish.
                while (true) {
                    if (stack.isEmpty()) {
                        ws();
                        if (i != s.length()) throw bad();
                        return value;
                    }
                    Object top = stack.peek();
                    ws();
                    if (top instanceof List) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) top;
                        list.add(value);
                        char d = peek();
                        if (d == ',') { i++; break; }
                        if (d == ']') { i++; value = stack.pop(); continue; }
                        throw bad();
                    }
                    JsObject obj = (JsObject) top;
                    obj.put(keys.pop(), value); // the last of two equal keys wins
                    char e = peek();
                    if (e == ',') { i++; ws(); keys.push(key()); break; }
                    if (e == '}') { i++; value = stack.pop(); continue; }
                    throw bad();
                }
            }
        }

        // A property name and its colon.
        private String key() {
            if (peek() != '"') throw bad();
            String k = str();
            ws();
            if (peek() != ':') throw bad();
            i++;
            return k;
        }

        private Object scalar() {
            char c = peek();
            if (c == '"') return str();
            if (c == 't') { lit("true"); return Boolean.TRUE; }
            if (c == 'f') { lit("false"); return Boolean.FALSE; }
            if (c == 'n') { lit("null"); return null; }
            return number();
        }

        private void lit(String word) {
            if (!s.startsWith(word, i)) throw bad();
            i += word.length();
        }

        private Double number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            if (i >= s.length()) throw bad();
            if (s.charAt(i) == '0') i++;
            else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') { while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++; }
            else throw bad();
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int f = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == f) throw bad();
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int f = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == f) throw bad();
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private String str() {
            i++; // opening quote
            StringBuilder sb = null;
            int run = i;
            while (true) {
                if (i >= s.length()) throw bad();
                char c = s.charAt(i);
                if (c == '"') {
                    String tail = s.substring(run, i);
                    i++;
                    return sb == null ? tail : sb.append(tail).toString();
                }
                if (c < 0x20) throw bad();
                if (c != '\\') { i++; continue; }
                if (sb == null) sb = new StringBuilder();
                sb.append(s, run, i);
                i++;
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw bad();
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            char h = s.charAt(i + k);
                            int v = h >= '0' && h <= '9' ? h - '0' : h >= 'a' && h <= 'f' ? h - 'a' + 10 : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                            if (v < 0) throw bad();
                            code = code * 16 + v;
                        }
                        i += 4;
                        sb.append((char) code); // a lone surrogate is kept, as in JavaScript
                    }
                    default -> throw bad();
                }
                run = i;
            }
        }
    }

    /**
     * JSON.stringify for the values the store builds: null, Boolean, numbers, text, lists, JsObject, and IJson objects.
     * A key whose value is Js.UNDEFINED is left out, as stringify leaves out undefined.
     * It walks the value with a stack of its own, not by calling itself, so a value nested tens of thousands deep (a body
     * of 100 KB can hold one) is written, and never overflows the thread's stack.
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        Deque<Frame> open = new ArrayDeque<>();
        Object next = value;
        while (true) {
            // Write one value; a container opens a frame and its first member is fetched below.
            if (next instanceof IJson j) { next = j.toJson(); continue; }
            else if (next instanceof Map<?, ?> m) {
                sb.append('{');
                @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) m;
                open.push(new Frame(typed.entrySet().iterator(), null));
            }
            else if (next instanceof String text) quote(sb, text);
            else if (next instanceof Iterable<?> list) { sb.append('['); open.push(new Frame(null, list.iterator())); }
            else if (next == null || next == Js.UNDEFINED) sb.append("null");
            else if (next instanceof Boolean b) sb.append(b ? "true" : "false");
            else if (next instanceof Double d) sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : Js.num(d));
            else if (next instanceof Integer || next instanceof Long) sb.append(Js.num(((Number) next).doubleValue()));
            else throw new IllegalStateException("cannot write " + next.getClass());
            // Find the next value to write: the next member of the innermost open container, closing finished ones.
            while (true) {
                if (open.isEmpty()) return sb.toString();
                Frame frame = open.peek();
                if (frame.members != null) {
                    boolean found = false;
                    while (frame.members.hasNext()) {
                        Map.Entry<String, Object> kv = frame.members.next();
                        if (Js.isUndefined(kv.getValue())) continue;
                        if (!frame.first) sb.append(',');
                        frame.first = false;
                        quote(sb, kv.getKey());
                        sb.append(':');
                        next = kv.getValue();
                        found = true;
                        break;
                    }
                    if (found) break;
                    sb.append('}');
                } else {
                    if (frame.items.hasNext()) {
                        if (!frame.first) sb.append(',');
                        frame.first = false;
                        next = frame.items.next();
                        break;
                    }
                    sb.append(']');
                }
                open.pop();
            }
        }
    }

    private static final class Frame {
        final Iterator<Map.Entry<String, Object>> members;
        final Iterator<?> items;
        boolean first = true;
        Frame(Iterator<Map.Entry<String, Object>> members, Iterator<?> items) { this.members = members; this.items = items; }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    boolean lone = Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
                    if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1))) lone = false;
                    if (c < 0x20 || lone) sb.append("\\u").append(String.format("%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
