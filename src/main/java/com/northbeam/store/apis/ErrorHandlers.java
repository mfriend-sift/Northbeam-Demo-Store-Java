package com.northbeam.store.apis;

import com.northbeam.store.lib.ApiException;
import com.northbeam.store.lib.Reply;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Last handler for what a route throws: every failure is JSON too, never an HTML page with a stack trace. */
@RestControllerAdvice
public class ErrorHandlers {
    @ExceptionHandler(ApiException.class)
    public void api(ApiException e, HttpServletResponse res) { Reply.error(e.status, e.getMessage()).writeTo(res); }

    @ExceptionHandler(Exception.class)
    public void other(Exception e, HttpServletResponse res) {
        if (e instanceof ErrorResponse spring && spring.getStatusCode().is4xxClientError()) { Reply.error(spring.getStatusCode().value(), "Bad request").writeTo(res); return; }
        System.err.println(e);
        e.printStackTrace();
        Reply.error(500, "Something went wrong").writeTo(res);
    }
}
