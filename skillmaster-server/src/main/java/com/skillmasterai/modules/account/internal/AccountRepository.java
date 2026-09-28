package com.skillmasterai.modules.account.internal;

import com.skillmasterai.modules.account.AccountDirectory;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads {@code app_user} — M1's table.
 *
 * <p>Read-only, and that is the whole of P0's M1: the seeded identities of
 * {@code V2__seed_owner_and_namespaces.sql} are the only users that exist until registration
 * lands in P1.
 *
 * <p>Public but confined to {@code internal}; see
 * {@code ArchitectureTest.nothingOutsideAModuleMayReferenceAnotherModulesInternals}.
 */
public final class AccountRepository implements AccountDirectory {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> handleOf(String userId) {
        return jdbc.sql("SELECT handle FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }
}
