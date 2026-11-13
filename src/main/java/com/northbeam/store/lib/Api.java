package com.northbeam.store.lib;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Function;

/** How a route runs: check its parameters, then run the handler under the one store lock and answer what it built. */
public final class Api {
    private Api() {}

    public static void run(HttpServletRequest req, HttpServletResponse res, Function<HttpServletRequest, Reply> handler) {
        Reply reply;
        try {
            Http.checkParams(req);
            reply = Db.sync(() -> handler.apply(req));
        } catch (ApiException e) {
            reply = Reply.error(e.status, e.getMessage());
        }
        reply.writeTo(res);
    }
}
