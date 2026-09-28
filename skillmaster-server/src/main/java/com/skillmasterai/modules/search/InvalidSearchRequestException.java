package com.skillmasterai.modules.search;

/**
 * The query parameters do not describe a search this API performs.
 *
 * <p>Raised for a limit below one, a cursor this version cannot read, and a cursor that belongs to
 * a different ordering. All three are the caller's to fix, so all three are a 400 — and none of
 * them may be quietly repaired. A cursor that is silently ignored restarts the listing from the
 * beginning, which a client paginating a large result set experiences as an infinite loop.
 */
public class InvalidSearchRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidSearchRequestException(String message) {
        super(message);
    }
}
