package com.northbeam.store.lib;

/** The environment the store reads: DEMO=1 (set only by tools/run.sh) and the build and version labels. */
public final class Config {
    private Config() {}

    /** DEMO=1 enables /demo/*, the persona headers and CORS for the engine origin. */
    public static boolean demo() {
        String v = System.getProperty("DEMO");
        if (v == null) v = System.getenv("DEMO");
        return "1".equals(v);
    }

    public static String env(String name) {
        String v = System.getProperty(name);
        return v != null ? v : System.getenv(name);
    }
}
