package com.northbeam.store.lib;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;

/** How a route runs: check its parameters, then run the handler under the one store lock and answer what it built. */
public final class Api {
    private Api() {}

    public static ResponseEntity<byte[]> run(HttpServletRequest req, Function<HttpServletRequest, Reply> handler) {
        try {
            Http.checkParams(req);
            return Db.sync(() -> handler.apply(req)).entity();
        } catch (ApiException e) {
            return Reply.error(e.status, e.getMessage()).entity();
        }
    }
}
