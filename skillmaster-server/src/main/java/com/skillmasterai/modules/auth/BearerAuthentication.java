package com.skillmasterai.modules.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated principal for a request that carried an accepted bearer token.
 *
 * <p>{@link #getCredentials()} returns null on purpose. The default behaviour of an
 * authentication token is to carry the credential that proved identity, which here is the
 * bearer token itself — and a bearer token is a full credential: anything that can log it can
 * replay it. There is no code path that needs it back, so it is not retained.
 */
public final class BearerAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient AuthenticatedSubject subject;

    public BearerAuthentication(AuthenticatedSubject subject) {
        super(authorities(subject));
        this.subject = subject;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(AuthenticatedSubject subject) {
        return subject.scopes().stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority(Scopes.authority(scope)))
                .toList();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return subject;
    }
}
