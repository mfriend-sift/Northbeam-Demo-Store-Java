package com.northbeam.store.apis;

import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.Models.User;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Sessions;
import com.northbeam.store.lib.Verification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerificationApi {
    // Sends a verification code by email or sms (a stand-in sender: nothing leaves the process). The work is in Verification.
    @PostMapping("/api/verification/start")
    public void start(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return Reply.error(401, "Sign in first");
            Object channel = Js.get(Http.jsonBody(req), "channel");
            Verification.Result result = Verification.startVerification(user, channel, req);
            return Reply.json(result.http(), result.body());
        });
    }

    @PostMapping("/api/verification/confirm")
    public void confirm(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return Reply.error(401, "Sign in first");
            Object code = Js.get(Http.jsonBody(req), "code");
            if (!(code instanceof String text) || Js.trim(text).isEmpty()) return Reply.error(400, "code is required");
            Verification.Result result = Verification.confirmVerification(user, text, req);
            return Reply.json(result.http(), result.body());
        });
    }
}
