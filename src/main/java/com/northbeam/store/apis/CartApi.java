package com.northbeam.store.apis;

import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Cart;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.Models.CartLine;
import com.northbeam.store.lib.Models.Product;
import com.northbeam.store.lib.Models.User;
import com.northbeam.store.lib.Promo;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Sessions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CartApi {
    private static Reply signInFirst() { return Reply.error(401, "Sign in first"); }

    @PostMapping("/api/cart/items")
    public ResponseEntity<byte[]> addItem(HttpServletRequest http) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();

            JsObject body = Http.jsonBody(req);
            Object productId = Js.get(body, "productId");
            Object qty = Js.get(body, "qty");
            if (!Js.isUndefined(qty) && !(Js.isInteger(qty) && (Double) qty >= 1)) return Reply.error(400, "qty must be a whole number, 1 or more");
            Product product = null;
            if (productId instanceof String wanted) for (Product p : Db.products) if (p.id.equals(wanted)) { product = p; break; }
            if (product == null) return Reply.error(404, "Unknown product");

            List<CartLine> cart = Db.carts.get(user.id);
            if (cart == null) cart = new ArrayList<>();
            CartLine line = null;
            for (CartLine l : cart) if (l.productId.equals(product.id)) { line = l; break; }
            double added = Js.truthy(qty) ? (Double) qty : 1;
            if (line != null) line.qty += added;
            else { CartLine fresh = new CartLine(); fresh.productId = product.id; fresh.qty = added; cart.add(fresh); }
            Db.carts.put(user.id, cart);

            return Reply.json(200, Promo.cartView(user.id, req));
        });
    }

    // Removes one line from the cart (all of its quantity). The work is in Cart.removeCartLine.
    @DeleteMapping("/api/cart/items/{productId}")
    public ResponseEntity<byte[]> removeItem(HttpServletRequest http, @PathVariable String productId) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            Cart.Removal result = Cart.removeCartLine(user.id, productId);
            if (!result.ok()) return Reply.error(404, "That product is not in your cart");
            return Reply.json(200, Promo.cartView(user.id, req));
        });
    }

    @GetMapping("/api/cart")
    public ResponseEntity<byte[]> view(HttpServletRequest http) {
        return Api.run(http, req -> Reply.json(200, Promo.cartView(Sessions.of(req).userId, req)));
    }

    // Applies a promo code to the cart. The decision and its record are in Promo.applyPromo: 200 with the cart when accepted, 422 when rejected.
    @PostMapping("/api/cart/promo")
    public ResponseEntity<byte[]> applyPromo(HttpServletRequest http) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            Object code = Js.get(Http.jsonBody(req), "code");
            if (!(code instanceof String text) || Js.trim(text).isEmpty()) return Reply.error(400, "code is required");
            Promo.Outcome outcome = Promo.applyPromo(user, text, req);
            if (!outcome.status().equals("accepted")) return Reply.error(422, outcome.error());
            return Reply.json(200, Promo.cartView(user.id, req));
        });
    }

    @DeleteMapping("/api/cart/promo")
    public ResponseEntity<byte[]> removePromo(HttpServletRequest http) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            Http.jsonBody(req); // same Content-Type rule as the other routes (a body of another type is 415)
            Promo.removePromo(user, req);
            return Reply.json(200, Promo.cartView(user.id, req));
        });
    }
}
