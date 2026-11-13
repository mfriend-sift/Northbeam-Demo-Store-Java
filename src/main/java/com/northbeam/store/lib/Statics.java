package com.northbeam.store.lib;

import java.util.Map;

/** Content types for the storefront files, with the charset the browser should read text in. */
public final class Statics {
    private Statics() {}

    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "application/javascript; charset=utf-8"),
        Map.entry("mjs", "application/javascript; charset=utf-8"), Map.entry("json", "application/json"),
        Map.entry("md", "text/markdown; charset=utf-8"), Map.entry("txt", "text/plain"),
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"), Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"),
        Map.entry("webp", "image/webp"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
        Map.entry("xml", "application/xml"), Map.entry("map", "application/json"));

    /** The Content-Type for a file name, or null for an extension nobody listed (served as a download type). */
    public static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }
}
