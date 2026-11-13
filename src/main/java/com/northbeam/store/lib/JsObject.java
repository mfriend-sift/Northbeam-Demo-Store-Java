package com.northbeam.store.lib;

import java.util.LinkedHashMap;

/** One alias for "a JSON object": insertion order is the key order of the JSON written. */
public final class JsObject extends LinkedHashMap<String, Object> {
    private static final long serialVersionUID = 1L;

    public JsObject() { super(); }

    /** Fluent put, so a response reads as one expression. */
    public JsObject set(String key, Object value) { put(key, value); return this; }
}
