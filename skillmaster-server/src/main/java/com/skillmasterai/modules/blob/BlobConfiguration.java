package com.skillmasterai.modules.blob;

import com.skillmasterai.modules.blob.internal.PgByteaBlobStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M6's beans.
 *
 * <p>Every module wires itself rather than being assembled by {@code com.skillmasterai.config}.
 * That is not stylistic: the architecture test forbids any package outside a module from
 * referencing its {@code internal} package, so a wiring class elsewhere could not name
 * {@link PgByteaBlobStore} at all. Self-wiring is what keeps "the backend is invisible outside
 * this module" true, which is the whole point of the {@link BlobStore} seam.
 */
@Configuration(proxyBeanMethods = false)
public class BlobConfiguration {

    @Bean
    BlobStore blobStore(JdbcClient jdbc) {
        return new PgByteaBlobStore(jdbc);
    }
}
