package com.skillmasterai.config;

import com.skillmasterai.modules.search.RelevanceWeights;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands M8 the ranking weights it was configured with.
 *
 * <p>One bean, in the config layer, which looks like a detour and is not: M8's own wiring class
 * cannot read {@link SkillmasterProperties}, because the architecture rule forbids a module from
 * depending on {@code config}. Something above the modules has to do the translation, and this is
 * that something — the same shape as {@link SecurityConfig} building M3's validator from the
 * configured token.
 *
 * <p>The alternative, letting modules read configuration directly, would make every module's wiring
 * depend on the whole configuration surface rather than on the one value it was handed.
 */
@Configuration(proxyBeanMethods = false)
class RankingConfig {

    @Bean
    RelevanceWeights relevanceWeights(SkillmasterProperties properties) {
        return properties.search().weights();
    }
}
