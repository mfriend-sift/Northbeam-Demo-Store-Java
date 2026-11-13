package com.northbeam.store.lib;

/** A request the client got wrong, answered as JSON with its own status. */
public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;

    public ApiException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }
}
