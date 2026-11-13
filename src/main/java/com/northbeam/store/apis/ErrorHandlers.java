package com.northbeam.store.apis;

import com.northbeam.store.lib.ApiException;
import com.northbeam.store.lib.Reply;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Last handler for what a route throws: every failure is JSON too, never an HTML page with a stack trace. */
@RestControllerAdvice
public class ErrorHandlers {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<byte[]> api(ApiException e) { return Reply.error(e.status, e.getMessage()).entity(); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> other(Exception e) {
        if (e instanceof ErrorResponse spring && spring.getStatusCode().is4xxClientError()) return Reply.error(spring.getStatusCode().value(), "Bad request").entity();
        System.err.println(e);
        e.printStackTrace();
        return Reply.error(500, "Something went wrong").entity();
    }
}
