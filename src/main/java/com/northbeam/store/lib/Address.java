package com.northbeam.store.lib;

/** One address rule, shared by orders, accounts and signup. Every field is free text checked by Text.validText. */
public final class Address {
    private Address() {}

    public static final String[] FIELDS = { "name", "address1", "city", "region", "country", "zipcode" };

    public static boolean validAddress(Object a) {
        if (!(a instanceof JsObject o)) return false;
        for (String f : FIELDS) if (!Text.validText(Js.get(o, f))) return false;
        return true;
    }

    /** A stored account address keeps only the six known fields. */
    public static JsObject pickAddress(JsObject a) {
        JsObject picked = new JsObject();
        for (String f : FIELDS) picked.put(f, Js.get(a, f));
        return picked;
    }
}
