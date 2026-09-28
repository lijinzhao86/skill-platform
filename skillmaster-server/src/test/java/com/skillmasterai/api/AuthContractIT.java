package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * T5 in test-plan.md: the shapes a client depends on before it has a token.
 *
 * <p>These are not incidental. §1.5 records that the discovery channel has no authentication
 * whatsoever, so a client's very first contact with this server is unauthenticated and the 401
 * is how it learns where the authorization server is. Getting the challenge wrong breaks the
 * only bootstrap path there is.
 */
class AuthContractIT extends AbstractIT {

    private static final String RESOURCE_METADATA =
            "resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"";

    @Test
    void anonymousRequestIsChallengedWithTheResourceMetadataUrl() {
        HttpResponse<String> response = get("/v1/skills", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .as("the challenge is how a client discovers the authorization server")
                .startsWith("Bearer")
                .contains(RESOURCE_METADATA)
                .as("no credential was presented, so nothing is reported as invalid")
                .doesNotContain("error=");
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void rejectedTokenIsReportedAsInvalidToken() {
        HttpResponse<String> response = get("/v1/skills", "not-the-configured-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"invalid_token\"")
                .contains(RESOURCE_METADATA);
    }

    @Test
    void challengeDoesNotEchoTheRequestPath() {
        // A 401 must not become a way to probe which resources exist.
        HttpResponse<String> response = get("/v1/skills/01M3HTG7GCCVBGRPAFFSVSF12W", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .doesNotContain("01M3HTG7GCCVBGRPAFFSVSF12W")
                .contains("Authentication is required.");
    }

    @Test
    void anAcceptedTokenIsNeitherRejectedNorDenied() {
        // Authorization passed. There is no controller for this route until S5, so the request
        // then falls through to 404 — what matters here is that it is not 401 or 403, which is
        // the observable difference between "authenticated with enough scope" and everything else.
        assertThat(token())
                .as("the test profile must supply the same token the server was started with")
                .isNotBlank();
        HttpResponse<String> response = get("/v1/skills", token());

        assertThat(response.statusCode())
                .as("response body was: %s", response.body())
                .isNotIn(401, 403);
    }

    @Test
    void healthIsReachableWithoutAToken() {
        // The load balancer has no credentials.
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void anUnknownPathIsDeniedRatherThanDisclosed() {
        // Default-deny: a path with no rule is still not public.
        assertThat(get("/not-a-real-endpoint", null).statusCode()).isEqualTo(401);
    }
}
