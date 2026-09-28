package com.skillmasterai.config;

import com.skillmasterai.modules.gateway.GatewaySettings;
import com.skillmasterai.usecase.PublishGatewaySkillUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the gateway skill exist, and hands M11 the two values it was configured with.
 *
 * <p>The bootstrap runs at startup rather than on first request. §1.5's channel is how a machine
 * that has never authenticated learns where to authenticate, so its content has to be there
 * <em>before</em> the first client asks — and doing it once at start keeps a request from paying
 * for the read. It is safe to run every time because publishing identical content is a no-op
 * (ADR 0005), so a restart with an unchanged source writes nothing.
 *
 * <p>A failure here is fatal on purpose. A server that starts without a published gateway serves a
 * 404 to every client trying to bootstrap, and that is indistinguishable from a client-side
 * mistake — better to refuse to start and say so.
 */
@Configuration(proxyBeanMethods = false)
class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    @Bean
    GatewaySettings gatewaySettings(SkillmasterProperties properties) {
        // Blank rather than absent: the V2 index's schema URL is genuinely unknown here (see
        // GatewayIndex.V2), and a missing property should mean "omit the field", not "fail to
        // start". An empty string is how YAML says that without inventing a URL.
        String schemaUrl = properties.gateway().indexSchemaUrl();
        return new GatewaySettings(properties.publicBaseUrl(),
                schemaUrl == null || schemaUrl.isBlank() ? null : schemaUrl);
    }

    @Bean
    ApplicationRunner gatewayBootstrap(PublishGatewaySkillUseCase publishGateway,
            SkillmasterProperties properties) {
        return args -> {
            var published = publishGateway.publish(properties.publicBaseUrl());
            log.info("gateway skill {} at version {} (created: {})", published.name(),
                    published.digest(), published.created());
        };
    }
}
