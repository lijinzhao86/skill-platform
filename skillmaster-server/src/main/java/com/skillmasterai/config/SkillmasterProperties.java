package com.skillmasterai.config;

import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.search.RelevanceWeights;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything this deployment configures, under {@code skillmaster.*}.
 *
 * <p>Bound by constructor, so a missing required value fails at startup with the property name
 * rather than turning into a null somewhere on a request path.
 *
 * <p>Note where these values are <em>used</em>: a module's wiring class cannot read this record.
 * The architecture rule forbids any module from depending on {@code config}, because "what this
 * deployment configures" is not a thing a module should be able to reach for. So a value a module
 * needs is built into a bean by a class in this package and passed in — see
 * {@link RankingConfig}.
 *
 * @param publicBaseUrl the base URL the outside world reaches this server at. Used for absolute
 *                      URLs we publish — the {@code resource_metadata} parameter in the 401/403
 *                      challenges, and the address written into the gateway skill. It cannot be
 *                      derived from a request: behind a proxy the request's own host is not the
 *                      client's.
 * @param auth          how P0 authenticates (see {@code modules/auth})
 * @param search        how results are ranked (see {@code modules/search})
 * @param gateway       what the discovery channel publishes (see {@code modules/gateway})
 */
@ConfigurationProperties("skillmaster")
public record SkillmasterProperties(String publicBaseUrl, Auth auth, Search search, Gateway gateway) {

    public SkillmasterProperties {
        Objects.requireNonNull(publicBaseUrl,
                "skillmaster.public-base-url is required: absolute URLs we publish depend on it");
        // Normalised here, at the one point the property enters the application, because every
        // consumer appends a path that already begins with a slash. With a trailing one left in,
        // the gateway index advertises "https://host//gateway/SKILL.md" — and "//" is rejected
        // outright by StrictHttpFirewall, so the URL a client was told to fetch is unfetchable.
        publicBaseUrl = stripTrailingSlashes(publicBaseUrl);
        Objects.requireNonNull(auth, "skillmaster.auth is required");
        Objects.requireNonNull(search, "skillmaster.search is required");
        Objects.requireNonNull(gateway, "skillmaster.gateway is required");
    }

    private static String stripTrailingSlashes(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }

    /**
     * @param indexSchemaUrl the {@code $schema} for the V2 discovery index. <strong>Empty by
     *                       default, and that is the honest value</strong>: §1.5 records that the
     *                       URL lives under {@code agentskills.io} as the "v0.2.0 draft" and that
     *                       it does not currently resolve, so the exact string cannot be verified
     *                       from here. Blank means the field is omitted from the document rather
     *                       than filled with a guess. P0b's CLI check is what settles it.
     */
    public record Gateway(@DefaultValue String indexSchemaUrl) {
    }

    /** @param weights the per-field values a text hit is worth; see {@link RelevanceWeights} */
    public record Search(RelevanceWeights weights) {
        public Search {
            Objects.requireNonNull(weights, "skillmaster.search.weights is required");
        }
    }

    /**
     * @param staticToken   P0's single bearer token. No default: a default would be a working
     *                      credential committed to a public repository.
     * @param subjectUserId the ULID of the {@code app_user} this token acts as. An id, not a
     *                      handle, so renaming the user cannot silently change who the token is.
     * @param scopes        the scopes the token carries
     */
    public record Auth(String staticToken, String subjectUserId,
            @DefaultValue({Scopes.SKILLS_WRITE, Scopes.SKILLS_READ}) Set<String> scopes) {
    }
}
