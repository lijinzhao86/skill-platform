package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmasterai.modules.gateway.GatewayIndex;
import java.util.List;

/**
 * The two discovery documents of §4.5, shaped for the wire.
 *
 * <p>The shapes are not ours to choose — they are the {@code skills} CLI's convention (§1.5), so
 * these records mirror it rather than prettifying it. Two details are worth knowing:
 *
 * <ul>
 *   <li><strong>{@code $schema} keeps its dollar.</strong> The global snake_case naming strategy
 *       does not touch it, but a record component named {@code $schema} is not a legal Java
 *       identifier, so the wire name is set explicitly. It is omitted entirely when null rather
 *       than sent as {@code null} — see {@link GatewayIndex.V2} for why the value may be unknown.</li>
 *   <li><strong>Nothing here is generated at request time.</strong> Both documents describe a
 *       published skill, and both are 404 when there is none — see {@code GatewayController}.</li>
 * </ul>
 */
final class GatewayIndexResponse {

    private GatewayIndexResponse() {
    }

    /** @param files paths relative to the skill root; the client composes the fetch URL from them */
    record V1(List<Skill> skills) {
        record Skill(String name, String description, List<String> files) {
        }

        static V1 of(GatewayIndex.V1 index) {
            return new V1(index.skills().stream()
                    .map(skill -> new Skill(skill.name(), skill.description(), skill.files()))
                    .toList());
        }
    }

    record V2(
            @JsonProperty("$schema") @JsonInclude(JsonInclude.Include.NON_NULL) String schema,
            List<Skill> skills) {

        record Skill(String name, String type, String description, String url, String digest) {
        }

        static V2 of(GatewayIndex.V2 index) {
            return new V2(index.schema(), index.skills().stream()
                    .map(skill -> new Skill(skill.name(), skill.type(), skill.description(),
                            skill.url(), skill.digest()))
                    .toList());
        }
    }
}
