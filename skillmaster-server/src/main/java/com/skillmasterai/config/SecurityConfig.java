package com.skillmasterai.config;

import com.skillmasterai.modules.auth.BearerAuthenticationEntryPoint;
import com.skillmasterai.modules.auth.BearerTokenSecurityContextRepository;
import com.skillmasterai.modules.auth.InsufficientScopeAccessDeniedHandler;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.auth.StaticTokenValidator;
import com.skillmasterai.modules.auth.TokenValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires M3 into the servlet chain: who a caller is, and what a request needs.
 *
 * <p>Default-deny. Every endpoint is private unless a rule below says otherwise, so forgetting
 * to protect a new path is a 401 rather than an open door.
 *
 * <p>What this class is <em>not</em> allowed to decide is whether a caller may see a particular
 * skill. That is visibility, it belongs to M4, and it renders as 404 (see
 * {@code InsufficientScopeAccessDeniedHandler}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    /**
     * P0 resolves one static token from configuration. P1 keeps this chain and swaps only the
     * validator for one backed by the authorization server — which is the entire reason
     * {@link TokenValidator} is an interface from v1 (see §4.4's rule about client lookup).
     */
    @Bean
    TokenValidator tokenValidator(SkillmasterProperties properties) {
        SkillmasterProperties.Auth auth = properties.auth();
        return new StaticTokenValidator(auth.staticToken(), auth.subjectUserId(), auth.scopes());
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, TokenValidator tokenValidator,
            SkillmasterProperties properties, ObjectMapper objectMapper) throws Exception {
        String baseUrl = properties.publicBaseUrl();

        http
                // No cookies and no browser session: every request carries its own token, so
                // there is no ambient authority for a cross-site request to borrow.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Health is unauthenticated so a load balancer can probe it.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The discovery channel (§1.5) and the gateway skill's own routes (§4.5).
                        // Anonymous by design, not by omission: this is how a machine that has
                        // never authenticated learns where to authenticate, so requiring a token
                        // would make it unreachable by exactly the clients it exists for. §1.5
                        // checked the convention for both authentication and gating and found
                        // neither, which is why publishing one public artefact through it is safe:
                        // there is nothing here but the gateway, and the gateway only says where
                        // the API is.
                        .requestMatchers("/.well-known/**", "/gateway/**").permitAll()
                        // Read for GET, write for everything else. Scopes.requiredForMethod is
                        // the same function the 403 challenge uses to name the missing scope, so
                        // the challenge cannot advertise a scope that is not actually enforced —
                        // and the contract test fails if the two ever drift apart.
                        .requestMatchers(HttpMethod.GET, "/v1/**").hasAuthority(readAuthority())
                        .requestMatchers("/v1/**").hasAuthority(writeAuthority())
                        .anyRequest().authenticated())
                // The context comes from the token, via the repository — see that class for why
                // this is not a custom filter that authenticates on its own.
                .securityContext(context -> context
                        .securityContextRepository(
                                new BearerTokenSecurityContextRepository(tokenValidator)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                new BearerAuthenticationEntryPoint(baseUrl, objectMapper))
                        .accessDeniedHandler(
                                new InsufficientScopeAccessDeniedHandler(baseUrl, objectMapper)));

        return http.build();
    }

    private static String readAuthority() {
        return Scopes.authority(Scopes.requiredForMethod(HttpMethod.GET.name()));
    }

    private static String writeAuthority() {
        return Scopes.authority(Scopes.requiredForMethod(HttpMethod.POST.name()));
    }
}
