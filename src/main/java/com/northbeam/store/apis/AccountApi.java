package com.northbeam.store.apis;

import com.northbeam.store.lib.AccountUpdate;
import com.northbeam.store.lib.Address;
import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.Mailer;
import com.northbeam.store.lib.Models.User;
import com.northbeam.store.lib.Password;
import com.northbeam.store.lib.PaymentMethods;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Sessions;
import com.northbeam.store.lib.Text;
import com.northbeam.store.lib.Verification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountApi {
    // Billing and default shipping address, when set (the keys are absent otherwise).
    private static void addAddresses(JsObject o, User u) {
        if (u.billingAddress != null) o.set("billingAddress", u.billingAddress);
        if (u.shippingAddress != null) o.set("shippingAddress", u.shippingAddress);
    }

    private static Reply signInFirst() { return Reply.error(401, "Sign in first"); }

    @GetMapping("/api/account")
    public void get(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            JsObject shown = new JsObject().set("id", user.id).set("name", user.name).set("email", user.email).set("phone", user.phone)
                .set("createdAt", user.createdAt).set("verified", user.verified);
            addAddresses(shown, user);
            if (user.paymentMethod != null) shown.set("paymentMethod", PaymentMethods.publicPaymentMethod(user.paymentMethod));
            return Reply.json(200, new JsObject().set("user", shown).set("notices", Mailer.noticesFor(user)));
        });
    }

    @PutMapping("/api/account")
    public void update(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();

            JsObject body = Http.jsonBody(req);
            Object name = Js.get(body, "name");
            Object email = Js.get(body, "email");
            Object phone = Js.get(body, "phone");
            Object billingAddress = Js.get(body, "billingAddress");
            Object shippingAddress = Js.get(body, "shippingAddress");
            if (!Js.truthy(name) && !Js.truthy(email) && !Js.truthy(phone) && Js.isUndefined(billingAddress) && Js.isUndefined(shippingAddress))
                return Reply.error(400, "Send name, email, phone, or an address");
            if (!Js.isUndefined(billingAddress) && !Address.validAddress(billingAddress)) return Reply.error(400, "billingAddress" + AuthApi.INCOMPLETE_ADDRESS);
            if (!Js.isUndefined(shippingAddress) && !Address.validAddress(shippingAddress)) return Reply.error(400, "shippingAddress" + AuthApi.INCOMPLETE_ADDRESS);
            if (Js.isPresent(email) && !(email instanceof String)) return Reply.error(400, "email must be text");
            if (Js.isPresent(phone) && !(phone instanceof String)) return Reply.error(400, "phone must be text");
            if (Js.isPresent(name) && !Text.validText(name)) return Reply.error(400, "Enter a name (text, at most 200 characters)");
            if (Js.truthy(email) && !AuthApi.EMAIL_SHAPE.matcher((String) email).matches()) return Reply.error(400, "Enter a valid email address");
            String normalizedPhone = Js.truthy(phone) ? AuthApi.normalizePhone((String) phone) : null;
            if (Js.truthy(phone) && normalizedPhone == null) return Reply.error(400, "Enter a valid phone number, for example 415-555-0123");
            User taken = Js.truthy(email) ? Db.findUserByEmail(email) : null;
            if (taken != null && !taken.id.equals(user.id)) return Reply.error(409, "An account with that email already exists");

            if (Js.truthy(name)) user.name = (String) name;
            boolean contactChanged = (Js.truthy(email) && !email.equals(user.email)) || (Js.truthy(phone) && !normalizedPhone.equals(user.phone));
            if (Js.truthy(email)) user.email = (String) email;
            if (Js.truthy(phone)) user.phone = normalizedPhone;
            if (contactChanged) Verification.resetVerification(user); // a new email or phone is not verified yet
            if (!Js.isUndefined(billingAddress)) user.billingAddress = Address.pickAddress((JsObject) billingAddress);
            if (!Js.isUndefined(shippingAddress)) user.shippingAddress = Address.pickAddress((JsObject) shippingAddress);

            AccountUpdate.updateAccount(user, req);
            JsObject shown = new JsObject().set("id", user.id).set("name", user.name).set("email", user.email).set("phone", user.phone);
            addAddresses(shown, user);
            return Reply.json(200, new JsObject().set("user", shown));
        });
    }

    // The saved card on file: a token (kept server-side, never returned), the BIN and the last four digits. Absent until saved.
    @PutMapping("/api/account/payment-method")
    public void savePaymentMethod(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            PaymentMethods.SaveResult result = PaymentMethods.savePaymentMethod(user, Http.jsonBody(req));
            if (!result.ok()) return Reply.error(400, result.error());
            AccountUpdate.updateAccount(user, req);
            return Reply.json(200, new JsObject().set("paymentMethod", PaymentMethods.publicPaymentMethod(result.paymentMethod())));
        });
    }

    @DeleteMapping("/api/account/payment-method")
    public void removePaymentMethod(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            PaymentMethods.removePaymentMethod(user);
            AccountUpdate.updateAccount(user, req);
            return Reply.json(200, new JsObject().set("ok", true));
        });
    }

    @PutMapping("/api/account/password")
    public void changePassword(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();

            JsObject body = Http.jsonBody(req);
            Object currentPassword = Js.get(body, "currentPassword");
            Object newPassword = Js.get(body, "newPassword");
            if (!(currentPassword instanceof String c && !c.isEmpty()) || !(newPassword instanceof String n && !n.isEmpty()))
                return Reply.error(400, "currentPassword and newPassword are required");
            Password.Outcome outcome = Password.changePassword(user, (String) currentPassword, (String) newPassword, req);
            if (!outcome.status().equals("success")) return Reply.error(400, "Current password is incorrect");

            Mailer.sendSecurityNotice(user, "password_changed");
            return Reply.json(200, new JsObject().set("ok", true));
        });
    }
}
