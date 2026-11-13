package com.northbeam.store.lib;

/** What a domain object gives the JSON writer: its own JSON shape, keys in the order the contract documents. */
public interface IJson {
    JsObject toJson();
}
