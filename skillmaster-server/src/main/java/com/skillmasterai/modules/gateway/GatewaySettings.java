package com.skillmasterai.modules.gateway;

/**
 * What a deployment tells M11 about itself.
 *
 * <p>A record rather than two constructor arguments because it is built by {@code config}: a
 * module's wiring class may not read {@code SkillmasterProperties} (the architecture rule forbids
 * any module from depending on {@code config}), so a configured value reaches a module as a bean
 * someone above it assembled. The same shape as the search weights.
 *
 * @param publicBaseUrl  the address clients reach this deployment at. Absolute URLs in the index
 *                       are built from it, and it is what replaces the source's placeholder. It
 *                       cannot come from a request: behind a proxy the request's own host is not
 *                       the client's.
 * @param indexSchemaUrl the {@code $schema} to declare in the V2 index, or null to omit the field.
 *                       Nullable because the real value is unverifiable from here — see
 *                       {@link GatewayIndex.V2}
 */
public record GatewaySettings(String publicBaseUrl, String indexSchemaUrl) {
}
