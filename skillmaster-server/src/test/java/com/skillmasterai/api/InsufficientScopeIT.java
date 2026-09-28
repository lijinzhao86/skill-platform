package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The 403 half of T5, which needs a server whose token carries fewer scopes than the request
 * requires — hence its own context rather than a case in {@link AuthContractIT}.
 *
 * <p>It also pins the pairing that {@code Scopes.requiredForMethod} exists to keep honest: the
 * scope the challenge names must be the scope the authorization rule actually enforces. If
 * someone changes one and not the other, this test fails on the {@code scope="..."} value.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "skillmaster.auth.scopes=skills:write")
class InsufficientScopeIT extends AbstractIT {

    @Test
    void aTokenWithoutTheReadScopeIsDeniedAndToldWhichScopeItNeeds() {
        HttpResponse<String> response = get("/v1/skills", token());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"skills:read\"")
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"");
        assertThat(response.body()).contains("\"code\":\"insufficient_scope\"");
    }
}
