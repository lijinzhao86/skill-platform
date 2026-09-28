package com.skillmasterai.modules.auth;

import com.skillmasterai.common.Ulid;
import java.util.Set;

/**
 * Who a request is from, once its token has been accepted.
 *
 * <p>Deliberately minimal: the caller's user id and the scopes the token carries. Nothing
 * about the token itself, and nothing about permissions — whether this subject may read a
 * particular skill is M4's decision, made in the use case, not here. Keeping the two apart is
 * what allows "unauthorized" to be rendered as 404 instead of 403 (see the distribution
 * module) rather than being decided by the security layer.
 *
 * @param userId the ULID of an {@code app_user} row
 * @param scopes the scopes the token grants, e.g. {@code skills:read}
 */
public record AuthenticatedSubject(String userId, Set<String> scopes) {

    public AuthenticatedSubject {
        Ulid.requireValid(userId, "authenticated subject's user id");
        scopes = Set.copyOf(scopes);
    }
}
