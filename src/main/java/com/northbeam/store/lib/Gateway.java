package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Charge;

/**
 * Stand-in for a payment gateway. Never receives raw card numbers: the browser sends a token,
 * plus the card BIN and last four digits the gateway returned when it tokenized the card.
 */
public final class Gateway {
    private Gateway() {}

    public static Charge chargeCard(double amount, String currency, String token) {
        // The amount and currency are what a real gateway would be sent; this stand-in only looks at the token.
        Charge c = new Charge();
        if (token.equals("tok_unavailable")) throw new IllegalStateException("gateway unavailable");
        c.id = Db.nextChargeId();
        if (token.equals("tok_declined")) { c.status = "failed"; c.declineCode = "insufficient_funds"; }
        else c.status = "succeeded";
        return c;
    }
}
