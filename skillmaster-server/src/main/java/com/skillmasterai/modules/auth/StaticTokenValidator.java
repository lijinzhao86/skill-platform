package com.skillmasterai.modules.auth;

import com.skillmasterai.common.Ulid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/**
 * P0's only {@link TokenValidator}: one token from configuration, one subject, one scope set.
 *
 * <p>Everything it needs is validated in the constructor so a misconfiguration stops the
 * application at startup rather than turning into 401s that look like a client problem. That
 * matters more than usual here: the token is the only thing standing between the API and the
 * data, and an unset value would otherwise mean "no one can authenticate" observed from the
 * outside as a broken client.
 */
public final class StaticTokenValidator implements TokenValidator {

    /**
     * A floor, not a policy: it stops a placeholder like {@code secret} from being deployed.
     * Real tokens arrive with the authorization server in P1 and are generated, not chosen.
     */
    private static final int MINIMUM_TOKEN_LENGTH = 16;

    private final byte[] expectedToken;
    private final AuthenticatedSubject subject;

    public StaticTokenValidator(String staticToken, String subjectUserId, Set<String> scopes) {
        if (staticToken == null || staticToken.isBlank()) {
            throw new IllegalStateException(
                    "skillmaster.auth.static-token is not set. P0 authenticates with a single "
                            + "static token; set SKILLMASTER_AUTH_STATIC_TOKEN (see the README).");
        }
        if (staticToken.length() < MINIMUM_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "skillmaster.auth.static-token is shorter than " + MINIMUM_TOKEN_LENGTH
                            + " characters, which is too short to be a real secret.");
        }
        this.expectedToken = staticToken.getBytes(StandardCharsets.UTF_8);
        // Fails at startup if the configured subject is not a ULID — a typo here would
        // otherwise surface later as "this user owns nothing".
        this.subject = new AuthenticatedSubject(
                Ulid.requireValid(subjectUserId, "skillmaster.auth.subject-user-id"), scopes);
    }

    @Override
    public Optional<AuthenticatedSubject> validate(String presentedToken) {
        if (presentedToken == null) {
            return Optional.empty();
        }
        byte[] candidate = presentedToken.getBytes(StandardCharsets.UTF_8);
        // Constant-time: a comparison that returns early leaks how much of the token matched.
        return MessageDigest.isEqual(expectedToken, candidate) ? Optional.of(subject) : Optional.empty();
    }
}
