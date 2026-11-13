package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.CartLine;
import com.northbeam.store.lib.Models.Product;
import java.util.ArrayList;
import java.util.List;

public final class Cart {
    private Cart() {}

    public record Removed(Product product, double qty) {}
    public record Removal(boolean ok, List<CartLine> cart, Removed removed) {}

    /**
     * The ONE place a line leaves a cart. The DELETE route calls it.
     * Returns ok with the updated lines and the line that left (its product and quantity), or not ok when the product is not in the cart.
     */
    public static Removal removeCartLine(String userId, String productId) {
        List<CartLine> cart = Db.carts.get(userId);
        if (cart == null) cart = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < cart.size(); i++) if (cart.get(i).productId.equals(productId)) { at = i; break; }
        if (at < 0) return new Removal(false, null, null);
        CartLine line = cart.get(at);
        cart.remove(at);
        Db.carts.put(userId, cart);
        Product product = null;
        for (Product p : Db.products) if (p.id.equals(productId)) { product = p; break; }
        return new Removal(true, cart, new Removed(product, line.qty));
    }
}
