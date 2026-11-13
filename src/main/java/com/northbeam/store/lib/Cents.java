package com.northbeam.store.lib;

public final class Cents {
    private Cents() {}

    /** A price in dollars as whole cents, rounded the way JavaScript's Math.round does. */
    public static double of(double dollars) { return Js.round(dollars * 100); }
}
