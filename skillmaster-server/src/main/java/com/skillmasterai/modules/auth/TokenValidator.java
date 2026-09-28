package com.skillmasterai.modules.auth;

import java.util.Optional;

/**
 * Turns a presented bearer token into a subject, or rejects it.
 *
 * <p>This is M3's seam, and it exists from v1 on purpose. P0 resolves a single static token
 * from configuration; P1 replaces that with tokens the authorization server signs and
 * refreshes. Nothing above this interface may know which of the two is in play — otherwise
 * adding the AS becomes a rewrite of the request path instead of a second implementation,
 * which is the same argument §4.4 makes about client lookup.
 *
 * <p>Implementations must not distinguish "no such token" from "expired" or "revoked" in what
 * they return: the caller gets an empty Optional and the response says only that the token was
 * not accepted.
 */
@FunctionalInterface
public interface TokenValidator {

    Optional<AuthenticatedSubject> validate(String presentedToken);
}
