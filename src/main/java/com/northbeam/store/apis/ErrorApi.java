package com.northbeam.store.apis;

import com.northbeam.store.lib.Reply;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Where the container sends an error it raised itself (a request it could not parse): the same JSON shape, never an HTML page. */
@RestController
public class ErrorApi implements ErrorController {
    @RequestMapping("/error")
    public void error(HttpServletRequest req, HttpServletResponse res) {
        Object code = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = code instanceof Integer i ? i : 500;
        HttpStatus known = HttpStatus.resolve(status);
        String message = switch (status) {
            case 400 -> "Bad request";
            case 404 -> "Not found";
            case 413 -> "Request body too large";
            default -> status >= 500 ? "Something went wrong" : known != null ? known.getReasonPhrase() : "Error";
        };
        Reply.error(status, message).writeTo(res);
    }
}
