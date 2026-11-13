package com.northbeam.store.lib;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** An answer: a status and the JSON text, built while the store lock is held so no state is read after it is released. */
public record Reply(int status, String body) {
    public static Reply json(int status, Object value) { return new Reply(status, Json.stringify(value)); }

    public static Reply error(int status, String message) { return new Reply(status, Json.stringify(new JsObject().set("error", message))); }

    public ResponseEntity<byte[]> entity() {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
            .contentLength(bytes.length)
            .body(bytes);
    }
}
