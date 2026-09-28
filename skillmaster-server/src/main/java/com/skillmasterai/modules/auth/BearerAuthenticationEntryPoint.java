package com.skillmasterai.modules.auth;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code 401} response shape of §4.1:
 * {@code WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/oauth-protected-resource"}.
 *
 * <p>The {@code resource_metadata} parameter is the whole point of the header — it is how a
 * client that has never spoken to us discovers where the authorization server is. It has to be
 * an absolute URL, which is why this class needs the configured public base rather than deriving
 * one from the request: behind a proxy the request's own idea of its host is not the client's.
 *
 * <p>A token that was presented and rejected additionally gets {@code error="invalid_token"}.
 * That is RFC 6750 and leaks nothing — it separates "you sent no credentials" from "what you
 * sent was not accepted", which is the difference between a client bug and a server misconfiguration.
 */
public final class BearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    private final String resourceMetadataUrl;
    private final ObjectMapper objectMapper;

    public BearerAuthenticationEntryPoint(String publicBaseUrl, ObjectMapper objectMapper) {
        this.resourceMetadataUrl = withoutTrailingSlash(publicBaseUrl) + RESOURCE_METADATA_PATH;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        boolean tokenWasPresented = BearerToken.presented(request);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(tokenWasPresented));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                ErrorCode.UNAUTHENTICATED,
                tokenWasPresented
                        ? "The presented token was not accepted."
                        : "Authentication is required."));
    }

    private String challenge(boolean tokenWasPresented) {
        StringBuilder challenge = new StringBuilder("Bearer");
        if (tokenWasPresented) {
            challenge.append(", error=\"invalid_token\"");
        }
        return challenge.append(", resource_metadata=\"").append(resourceMetadataUrl).append('"')
                .toString();
    }

    static String withoutTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
