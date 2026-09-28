package com.skillmasterai.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Rebuilds the test schema from the migrations every time a Spring context starts.
 *
 * <p>The test database is disposable by construction, so this keeps it honest: the migrations
 * are the only source of schema truth, and a database left in some other shape — by an older
 * test run, or by someone applying SQL by hand — gets corrected rather than producing
 * confusing failures. Imported by every test that starts a context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CleanMigrateFlyway {

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
