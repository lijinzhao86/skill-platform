package com.skillmasterai.modules.auth;

import java.util.Locale;

/**
 * The scopes this API understands, and the one place that decides which of them a request needs.
 *
 * <p>That last part is the reason this class exists rather than two constants. The 403 challenge
 * has to name the scope that was missing ({@code WWW-Authenticate: Bearer error="insufficient_scope",
 * scope="..."}), and the authorization rule has to require the same one. Computing it in two
 * places is how a challenge ends up advertising a scope the server does not actually check.
 */
public final class Scopes {

    private Scopes() {
    }

    public static final String SKILLS_READ = "skills:read";
    public static final String SKILLS_WRITE = "skills:write";

    /** Spring Security represents a granted scope as an authority prefixed {@code SCOPE_}. */
    public static String authority(String scope) {
        return "SCOPE_" + scope;
    }

    /**
     * Takes the method as a string rather than an {@code HttpMethod}: the access-denied handler
     * runs on arbitrary requests, and parsing an unrecognised verb into the enum would throw
     * while we are trying to produce an error response.
     */
    public static String requiredForMethod(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> SKILLS_READ;
            default -> SKILLS_WRITE;
        };
    }
}
