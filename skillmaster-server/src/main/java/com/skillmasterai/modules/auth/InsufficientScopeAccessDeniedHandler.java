package com.skillmasterai.modules.auth;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code 403} response shape of §4.1: a challenge naming the scope that was missing.
 *
 * <p>This is only reachable by a caller who <em>did</em> authenticate — an anonymous request is
 * handled by {@link BearerAuthenticationEntryPoint} first. It therefore means "your token is
 * valid but grants too little", which is why the design keeps it distinct from 401.
 *
 * <p>It is deliberately <em>not</em> used for "you may not see this skill". That decision belongs
 * to M4 in the use case, and it renders as 404, because a 403 would confirm the skill exists
 * (§4.2). Expressing visibility as an authority here is exactly the mistake that rule forbids.
 */
public final class InsufficientScopeAccessDeniedHandler implements AccessDeniedHandler {

    private final String resourceMetadataUrl;
    private final ObjectMapper objectMapper;

    public InsufficientScopeAccessDeniedHandler(String publicBaseUrl, ObjectMapper objectMapper) {
        this.resourceMetadataUrl = BearerAuthenticationEntryPoint.withoutTrailingSlash(publicBaseUrl)
                + "/.well-known/oauth-protected-resource";
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        String required = Scopes.requiredForMethod(request.getMethod());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer, error=\"insufficient_scope\", scope=\"" + required
                        + "\", resource_metadata=\"" + resourceMetadataUrl + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                ErrorCode.INSUFFICIENT_SCOPE,
                "This request requires the '" + required + "' scope."));
    }
}
