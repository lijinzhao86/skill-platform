package com.skillmasterai.modules.namespace;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.namespace.internal.NamespaceRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M4.
 *
 * <p>Takes {@link AccountDirectory} rather than M1's repository: a module may depend on another
 * module's published interface, never on its internals, and the architecture test enforces it.
 */
@Configuration(proxyBeanMethods = false)
public class NamespaceConfiguration {

    @Bean
    NamespaceRepository namespaceRepository(JdbcClient jdbc) {
        return new NamespaceRepository(jdbc);
    }

    @Bean
    NamespaceService namespaceService(NamespaceRepository repository, AccountDirectory accounts) {
        return new NamespaceService(repository, accounts);
    }
}
