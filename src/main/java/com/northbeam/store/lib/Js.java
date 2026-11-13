package com.northbeam.store.lib;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The few JavaScript rules the contract is written in: truthiness, whitespace, number printing and rounding.
 * Request values are plain objects (null, Boolean, Double, String, List, JsObject), so "the same answers as the
 * contract" means applying these rules by hand. Each helper says which JavaScript rule it follows.
 */
public final class Js {
    private Js() {}

    /** A key that is not in the body at all (JavaScript undefined). A key sent as JSON null is Java null. */
    public static final class UndefinedValue {
        private UndefinedValue() {}
        @Override public String toString() { return "undefined"; }
    }

    public static final UndefinedValue UNDEFINED = new UndefinedValue();

    public static Object get(JsObject o, String key) { return o.containsKey(key) ? o.get(key) : UNDEFINED; }
    public static boolean isUndefined(Object v) { return v == UNDEFINED; }
    /** x != null in JavaScript: neither null nor undefined. */
    public static boolean isPresent(Object v) { return v != null && v != UNDEFINED; }

    /** The JavaScript truthiness of a parsed JSON value. */
    public static boolean truthy(Object v) {
        if (v == null || v == UNDEFINED) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return !(d == 0 || Double.isNaN(d));
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    /** Number.isInteger */
    public static boolean isInteger(Object v) {
        return v instanceof Double d && !Double.isNaN(d) && !Double.isInfinite(d) && Math.floor(d) == d;
    }

    /** JavaScript WhiteSpace plus LineTerminator: what trim() removes and what \s matches. U+0085 is not in it. */
    public static final String WHITESPACE_CLASS = "\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

    public static boolean isSpace(char c) {
        return (c >= '\t' && c <= '\r') || c == ' ' || c == ' ' || c == ' ' || (c >= ' ' && c <= ' ')
            || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿';
    }

    public static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** String.prototype.toUpperCase: Java's full case mapping (dotless i, sharp s, ligatures) is the same one. */
    public static String toUpper(String s) { return s.toUpperCase(Locale.ROOT); }

    /** Math.round: halves go up. Exact for every double (Math.floor(x + 0.5) is wrong for 0.49999999999999994). */
    public static double round(double x) {
        double f = Math.floor(x);
        return x - f >= 0.5 ? f + 1 : f;
    }

    /** Number.prototype.toString for a finite or infinite double. */
    public static String num(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (d == 0) return "0";
        if (d == Double.POSITIVE_INFINITY) return "Infinity";
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity";
        String sign = d < 0 ? "-" : "";
        if (d < 0) d = -d;
        String r = Double.toString(d); // shortest digits that read back exactly (JDK 19 and later)
        int e = 0;
        int ePos = r.indexOf('E');
        if (ePos >= 0) { e = Integer.parseInt(r.substring(ePos + 1)); r = r.substring(0, ePos); }
        int dot = r.indexOf('.');
        String intPart = dot < 0 ? r : r.substring(0, dot);
        String frac = dot < 0 ? "" : r.substring(dot + 1);
        String digits = intPart + frac;
        int n = intPart.length() + e; // value = 0.<digits> * 10^n
        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') lead++;
        digits = digits.substring(lead);
        n -= lead;
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') end--;
        digits = digits.substring(0, end);
        if (digits.isEmpty()) return "0";
        int k = digits.length();
        String body;
        if (k <= n && n <= 21) body = digits + "0".repeat(n - k);
        else if (0 < n && n <= 21) body = digits.substring(0, n) + "." + digits.substring(n);
        else if (-6 < n && n <= 0) body = "0." + "0".repeat(-n) + digits;
        else {
            int x = n - 1;
            String exp = (x < 0 ? "-" : "+") + Math.abs(x);
            body = k == 1 ? digits + "e" + exp : digits.charAt(0) + "." + digits.substring(1) + "e" + exp;
        }
        return sign + body;
    }

    /**
     * String(x) for a parsed JSON value (a password or a card field can arrive as any type). A list is its members joined
     * by commas, so a list inside a list reads straight through; null and undefined members are empty. Walked with a stack of its own.
     */
    public static String str(Object v) {
        if (!(v instanceof List<?> top)) return scalar(v);
        StringBuilder sb = new StringBuilder();
        record Frame(List<?> list, int next) {}
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(top, 0));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (f.next() >= f.list().size()) continue;
            if (f.next() > 0) sb.append(',');
            stack.push(new Frame(f.list(), f.next() + 1));
            Object item = f.list().get(f.next());
            if (item instanceof List<?> inner) stack.push(new Frame(inner, 0));
            else if (item != null && item != UNDEFINED) sb.append(scalar(item));
        }
        return sb.toString();
    }

    private static String scalar(Object v) {
        if (v == null) return "null";
        if (v == UNDEFINED) return "undefined";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Double d) return num(d);
        if (v instanceof String s) return s;
        return "[object Object]";
    }

    private static final Pattern DECIMAL = Pattern.compile("\\A[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?\\z");

    /** Number(text) for the X-Demo-Now header: JavaScript's numeric string rules (decimal, exponent, 0x, 0o, 0b, Infinity). */
    public static double toNumber(String text) {
        if (text == null) return Double.NaN;
        String s = trim(text);
        if (s.isEmpty()) return 0;
        if (s.equals("Infinity") || s.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (s.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        if (s.length() > 2 && s.charAt(0) == '0' && "xXoObB".indexOf(s.charAt(1)) >= 0) {
            char r = Character.toLowerCase(s.charAt(1));
            int radix = r == 'x' ? 16 : r == 'o' ? 8 : 2;
            double acc = 0;
            for (int i = 2; i < s.length(); i++) {
                char c = s.charAt(i);
                int dgt = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : 99;
                if (dgt >= radix) return Double.NaN;
                acc = acc * radix + dgt;
            }
            return acc;
        }
        if (!DECIMAL.matcher(s).matches()) return Double.NaN;
        return Double.parseDouble(s);
    }

    /** [...text].length: code points, a surrogate pair counts once. */
    public static int codePoints(String s) { return s.codePointCount(0, s.length()); }
}
