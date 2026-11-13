package com.northbeam.store.lib;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * An answer: a status and the JSON text, built while the store lock is held so no state is read after it is released.
 * Routes write it straight to the servlet response (no Spring message converters: they read request headers the store does not control).
 */
public record Reply(int status, String body) {
    public static Reply json(int status, Object value) { return new Reply(status, Json.stringify(value)); }

    public static Reply error(int status, String message) { return new Reply(status, Json.stringify(new JsObject().set("error", message))); }

    /** Writes the whole answer. The container leaves the body out of a HEAD answer and keeps the length. */
    public void writeTo(HttpServletResponse res) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            res.setStatus(status);
            res.setContentType("application/json; charset=utf-8");
            res.setContentLength(bytes.length);
            res.getOutputStream().write(bytes);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
