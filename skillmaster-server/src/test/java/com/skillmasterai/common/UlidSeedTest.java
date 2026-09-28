package com.skillmasterai.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The seed migration's ULIDs are pasted-in constants, so nothing at compile time proves they
 * are well formed.
 *
 * <p>This reads the real migration file and validates every ULID-shaped token in it. The
 * obvious alternative — listing the expected ids in the test — would only validate the copy,
 * and would go on passing after someone hand-edited a character in the migration.
 */
class UlidSeedTest {

    private static final String SEED_MIGRATION = "db/migration/V2__seed_owner_and_namespaces.sql";

    /** Crockford base32: no I, L, O or U. */
    private static final Pattern ULID_SHAPED = Pattern.compile("\\b[0-9A-HJKMNP-TV-Z]{26}\\b");

    @Test
    void everyUlidInTheSeedMigrationIsValidAndDistinct() throws IOException {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = ULID_SHAPED.matcher(read(SEED_MIGRATION));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        assertThat(tokens)
                .as("three seeded users plus their three namespaces")
                .hasSize(6);

        for (String token : tokens) {
            assertThat(Ulid.isValid(token))
                    .as("seeded ULID %s must be well formed", token)
                    .isTrue();
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = UlidSeedTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("seed migration is not on the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
