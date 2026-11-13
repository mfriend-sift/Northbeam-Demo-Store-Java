package com.northbeam.store.apis;

import com.northbeam.store.lib.Address;
import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Clock;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.Login;
import com.northbeam.store.lib.Models.User;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Signing;
import com.northbeam.store.lib.Text;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthApi {
    // [^\s@]+@[^\s@]+\.[^\s@]+ with JavaScript's \s.
    public static final Pattern EMAIL_SHAPE = Pattern.compile(
        "\\A[^" + Js.WHITESPACE_CLASS + "@]+@[^" + Js.WHITESPACE_CLASS + "@]+\\.[^" + Js.WHITESPACE_CLASS + "@]+\\z");

    public static final String INCOMPLETE_ADDRESS = " is incomplete: every field must be non-empty text of at most 200 characters";

    /** Returns the phone in E.164 form (+14155550123), or null if it is not a plausible number. */
    public static String normalizePhone(String raw) {
        String digits = (raw == null ? "" : raw).replaceAll("[^0-9]", "");
        if (digits.length() == 10) return "+1" + digits;
        if (digits.length() >= 11 && digits.length() <= 15) return "+" + digits;
        return null;
    }

    public static JsObject userPublic(User user) {
        return new JsObject().set("id", user.id).set("name", user.name).set("email", user.email).set("phone", user.phone);
    }

    @PostMapping("/api/signup")
    public void signup(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            JsObject body = Http.jsonBody(req);
            Object name = Js.get(body, "name");
            Object email = Js.get(body, "email");
            Object phone = Js.get(body, "phone");
            Object password = Js.get(body, "password");
            Object billingAddress = Js.get(body, "billingAddress");
            Object shippingAddress = Js.get(body, "shippingAddress");
            if (!Text.validText(name) || !(email instanceof String e && !e.isEmpty()) || !(phone instanceof String ph && !ph.isEmpty()) || !(password instanceof String pw && !pw.isEmpty()))
                return Reply.error(400, "name, email, phone, and password are required");
            if (!EMAIL_SHAPE.matcher((String) email).matches()) return Reply.error(400, "Enter a valid email address");
            String normalizedPhone = normalizePhone((String) phone);
            if (normalizedPhone == null) return Reply.error(400, "Enter a valid phone number, for example 415-555-0123");
            if (!Js.isUndefined(billingAddress) && !Address.validAddress(billingAddress)) return Reply.error(400, "billingAddress" + INCOMPLETE_ADDRESS);
            if (!Js.isUndefined(shippingAddress) && !Address.validAddress(shippingAddress)) return Reply.error(400, "shippingAddress" + INCOMPLETE_ADDRESS);
            if (Db.findUserByEmail(email) != null) return Reply.error(409, "An account with that email already exists");

            User user = new User();
            user.id = Db.nextUserId();
            user.name = (String) name;
            user.email = (String) email;
            user.phone = normalizedPhone;
            user.passwordHash = Db.hash((String) password);
            user.createdAt = Clock.nowIso(req);
            if (!Js.isUndefined(billingAddress)) user.billingAddress = Address.pickAddress((JsObject) billingAddress);
            if (!Js.isUndefined(shippingAddress)) user.shippingAddress = Address.pickAddress((JsObject) shippingAddress);
            Db.users.add(user);
            Signing.signIn(req, user);

            return Reply.json(201, new JsObject().set("user", userPublic(user)));
        });
    }

    @PostMapping("/api/login")
    public void login(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            JsObject body = Http.jsonBody(req);
            Login.Outcome outcome = Login.attemptLogin(Js.get(body, "email"), Js.get(body, "password"), req);
            if (outcome.status().equals("failure")) return Reply.error(401, "Invalid email or password");
            return Reply.json(200, new JsObject().set("user", userPublic(outcome.user())));
        });
    }

    @PostMapping("/api/logout")
    public void logout(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            Signing.signOut(req);
            return Reply.json(200, new JsObject().set("ok", true));
        });
    }
}
