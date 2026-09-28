package com.skillmasterai.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Derives the request's security context from its bearer token.
 *
 * <p>This is the seam Spring Security provides for "where does the context come from", and it is
 * used here rather than a custom filter that authenticates on its own. That alternative was
 * tried and did not work: a filter placed immediately after {@code SecurityContextHolderFilter}
 * could read the token, validate it, and see the resulting authentication in
 * {@code SecurityContextHolder} — both before and after calling down the chain — while the
 * authorization filter downstream still treated the request as anonymous and answered 401. The
 * cause was not isolated (the context is deferred, and neither mutating it nor replacing it via
 * {@code setContext} propagated), and finding it was not worth more time once this approach was
 * confirmed working. Recorded because the failure is invisible from inside such a filter and
 * will look like a validator bug to whoever tries it next.
 *
 * <p>Letting the framework resolve the context removes the whole question: there is no filter
 * ordering to get right, and the context is installed by the filter that owns it.
 *
 * <p>Stateless by construction: nothing is stored, so {@link #saveContext} does nothing. Every
 * request carries its own token, which is also why the chain needs no CSRF token — there is no
 * ambient authority for a cross-site request to borrow.
 */
public final class BearerTokenSecurityContextRepository implements SecurityContextRepository {

    private final TokenValidator validator;
    private final SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();

    public BearerTokenSecurityContextRepository(TokenValidator validator) {
        this.validator = validator;
    }

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return contextFor(requestResponseHolder.getRequest());
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request,
            HttpServletResponse response) {
        // Nothing to remember between requests.
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return BearerToken.presented(request);
    }

    private SecurityContext contextFor(HttpServletRequest request) {
        String token = BearerToken.from(request);
        if (token == null) {
            return strategy.createEmptyContext();
        }
        return validator.validate(token)
                .map(subject -> {
                    SecurityContext context = strategy.createEmptyContext();
                    context.setAuthentication(new BearerAuthentication(subject));
                    return context;
                })
                .orElseGet(strategy::createEmptyContext);
    }
}
