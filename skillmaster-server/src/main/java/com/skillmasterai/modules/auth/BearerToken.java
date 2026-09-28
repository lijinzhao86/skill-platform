package com.skillmasterai.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Where a bearer token is allowed to come from.
 *
 * <p>Exactly one place: the {@code Authorization} header. §4.1 forbids accepting a token from a
 * query parameter, and the reason is not pedantry — the query string is the part of a request
 * most likely to be written to an access log, and a bearer token is a full credential.
 */
public final class BearerToken {

    private BearerToken() {
    }

    private static final String PREFIX = "Bearer ";

    /** @return the token, or null if the request carries none */
    public static String from(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Whether the caller tried to authenticate at all.
     *
     * <p>This is what separates "you sent nothing" from "what you sent was rejected" in the 401
     * challenge — a distinction that costs nothing and tells a client whether it has a bug or is
     * talking to a misconfigured server.
     */
    public static boolean presented(HttpServletRequest request) {
        return from(request) != null;
    }
}
