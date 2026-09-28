package com.skillmasterai.modules.namespace.internal;

import com.skillmasterai.modules.namespace.Namespace;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code namespace} and {@code namespace_member} — the only tables M4 owns.
 *
 * <p>Public but confined to this module's {@code internal} package; see the architecture test.
 * P0 reads only. Creating a namespace belongs with registration, which is P1 (M1): the seeded
 * identities in {@code V2__seed_owner_and_namespaces.sql} are the only ones that exist.
 */
public final class NamespaceRepository {

    private static final String COLUMNS = "id, slug, title, owner_user_id, visibility";

    private final JdbcClient jdbc;

    public NamespaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The namespace a user owns under a given slug — for the personal namespace, the slug is the
     * user's handle.
     *
     * <p>The handle is a parameter rather than a join against {@code app_user}: that table belongs
     * to M1, and §2.5 rule 1 says a module reads only its own. The caller resolves the handle
     * through {@link com.skillmasterai.modules.account.AccountDirectory} and passes it in, which is
     * also what keeps "personal namespace means slug equals handle" a rule of one place rather
     * than a SQL join that has to be repeated wherever a namespace is looked up.
     */
    public Optional<Namespace> findByOwnerAndSlug(String userId, String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM namespace"
                        + " WHERE owner_user_id = :userId AND slug = :slug")
                .param("userId", userId)
                .param("slug", slug)
                .query((rs, rowNum) -> new Namespace(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("owner_user_id"),
                        rs.getString("visibility")))
                .optional();
    }

    /** A namespace by its slug. Unique by constraint, so at most one row. */
    public Optional<Namespace> findBySlug(String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM namespace WHERE slug = :slug")
                .param("slug", slug)
                .query((rs, rowNum) -> new Namespace(
                        rs.getString("id"), rs.getString("slug"), rs.getString("title"),
                        rs.getString("owner_user_id"), rs.getString("visibility")))
                .optional();
    }

    public Optional<Namespace> findById(String namespaceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM namespace WHERE id = :id")
                .param("id", namespaceId)
                .query((rs, rowNum) -> new Namespace(
                        rs.getString("id"), rs.getString("slug"), rs.getString("title"),
                        rs.getString("owner_user_id"), rs.getString("visibility")))
                .optional();
    }
}
