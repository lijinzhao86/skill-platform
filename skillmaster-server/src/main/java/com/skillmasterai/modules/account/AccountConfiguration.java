package com.skillmasterai.modules.account;

import com.skillmasterai.modules.account.internal.AccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M1.
 *
 * <p>It exposes the {@link AccountDirectory} interface, never {@link AccountRepository}, so that a
 * caller cannot acquire the concrete type and reach past the seam. Other modules get whatever
 * {@code config} hands them, and this is the only bean of M1's that leaves the module.
 */
@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {

    @Bean
    AccountDirectory accountDirectory(JdbcClient jdbc) {
        return new AccountRepository(jdbc);
    }
}
