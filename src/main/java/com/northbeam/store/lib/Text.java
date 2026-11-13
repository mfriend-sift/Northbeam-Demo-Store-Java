package com.northbeam.store.lib;

/**
 * One text rule for every free-text field (names, address lines, token, and similar).
 * Blank = not a string, empty, or only Unicode White_Space plus the zero-width characters
 * U+200B, U+200C, U+200D, U+2060 and U+FEFF. Valid = not blank and at most 200 code points.
 */
public final class Text {
    private Text() {}

    public static final int MAX_FIELD = 200;

    // Unicode White_Space (this includes U+0085, which JavaScript's trim() does not remove) plus the zero-width characters.
    private static boolean isBlankChar(char c) {
        return Js.isSpace(c) || c == '\u0085' || c == '​' || c == '‌' || c == '‍' || c == '⁠';
    }

    public static boolean validText(Object v) {
        if (!(v instanceof String s)) return false;
        boolean blank = true;
        for (int i = 0; i < s.length(); i++) if (!isBlankChar(s.charAt(i))) { blank = false; break; }
        return !blank && Js.codePoints(s) <= MAX_FIELD;
    }
}
