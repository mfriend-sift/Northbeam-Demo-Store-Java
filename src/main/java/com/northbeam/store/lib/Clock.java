package com.northbeam.store.lib;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** "Now" for timestamps and time limits the store applies. X-Demo-Now (DEMO=1 only) overrides it. */
public final class Clock {
    private Clock() {}

    public static final String DEMO_NOW_KEY = "nb.demoNow";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static double nowMs(HttpServletRequest req) {
        return req.getAttribute(DEMO_NOW_KEY) instanceof Double demo ? demo : (double) System.currentTimeMillis();
    }

    public static String nowIso(HttpServletRequest req) { return iso(nowMs(req)); }

    /** new Date().toISOString() for a time the store does not take from the request (the mailer's outbox). */
    public static String isoNow() { return iso(System.currentTimeMillis()); }

    // A time outside years 0001 to 9999 has no four-digit year: the answer is the error 500, as the reference store's.
    private static final double MIN_MS = -62135596800000.0, MAX_MS = 253402300799999.0;

    private static String iso(double ms) {
        if (ms < MIN_MS || ms > MAX_MS) throw new IllegalStateException("time out of range");
        return ISO.format(Instant.ofEpochMilli((long) (ms < 0 ? Math.ceil(ms) : Math.floor(ms))));
    }
}
