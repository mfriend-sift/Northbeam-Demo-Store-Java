package com.northbeam.store.lib;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Codes {
    private Codes() {}

    /** Two verification codes compared in constant time. Codes of different length are never equal. */
    public static boolean same(String a, String b) {
        if (a.length() != b.length()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
