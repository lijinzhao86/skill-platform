package com.skillmasterai.modules.version.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.version.SkillMetadata;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill} — one of the three tables M7 owns. */
public final class SkillRepository {

    private final JdbcClient jdbc;

    public SkillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the skill, or updates the metadata of an existing live one. Returns its id.
     *
     * <p>Two deliberate omissions in the {@code DO UPDATE} clause:
     * <ul>
     *   <li><strong>{@code visibility} is not touched.</strong> §4.3 gives metadata its own
     *       endpoint; a republish that reset visibility would be a way to make a private skill
     *       public by accident.</li>
     *   <li><strong>{@code deleted_at} is not cleared</strong>, enforced by the {@code WHERE}. A
     *       soft-deleted skill therefore returns no row here, which is what lets the caller tell
     *       "deleted" from "created" and refuse instead of resurrecting (see
     *       {@link com.skillmasterai.modules.version.SkillDeletedException}).</li>
     * </ul>
     */
    public Optional<String> upsertLive(String namespaceId, SkillMetadata metadata, String createdBy,
            String at) {
        return jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, :namespaceId, :name, :title, :description, :frontmatter,
                        'private', :createdBy, :at, :at)
                ON CONFLICT (namespace_id, name) DO UPDATE
                   SET title = EXCLUDED.title,
                       description = EXCLUDED.description,
                       frontmatter = EXCLUDED.frontmatter,
                       updated_at = EXCLUDED.updated_at
                 WHERE skill.deleted_at IS NULL
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("namespaceId", namespaceId)
                .param("name", metadata.name())
                .param("title", metadata.title())
                .param("description", metadata.description())
                .param("frontmatter", metadata.frontmatterJson())
                .param("createdBy", createdBy)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * Moves the skill's current-version pointer.
     *
     * <p>Called only when a version was actually inserted. Moving it on a republish of identical
     * content would silently implement rollback, which §7 assigns to P2 and §4.3 gives its own
     * endpoint.
     */
    public void moveCurrentVersion(String skillId, String versionId, String at) {
        jdbc.sql("UPDATE skill SET current_version_id = :versionId, updated_at = :at WHERE id = :id")
                .param("versionId", versionId)
                .param("at", at)
                .param("id", skillId)
                .update();
    }

    /**
     * The skill, if it is live and lives in the given namespace.
     *
     * <p><strong>The namespace predicate is the whole authorization check, and that is the point
     * of putting it here.</strong> §4.2 requires an unreadable private skill to be a 404 rather
     * than a 403, because a 403 confirms it exists. Filtering in the same statement that finds the
     * row makes "not yours" and "not there" the same empty result <em>structurally</em>, rather
     * than two branches that a later edit could drift apart — there is no separate predicate to
     * forget, and nothing to render as a 403.
     *
     * <p>The caller supplies the namespace, so this is not a general "may I see it" query: M7
     * knows which namespace a skill is in, M4 knows which namespace the caller owns, and the
     * use-case layer is where those two facts are allowed to meet (§2.5).
     */
    public java.util.Optional<SkillRow> liveInNamespace(String skillId, String namespaceId) {
        return jdbc.sql("""
                SELECT id, namespace_id, name, title, description, frontmatter, visibility,
                       current_version_id
                FROM skill
                WHERE id = :id AND namespace_id = :namespaceId AND deleted_at IS NULL
                """)
                .param("id", skillId)
                .param("namespaceId", namespaceId)
                .query((rs, rowNum) -> new SkillRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("frontmatter"),
                        rs.getString("visibility"),
                        rs.getString("current_version_id")))
                .optional();
    }

    /**
     * The live skill with this name in this namespace, if there is one.
     *
     * <p>{@code UNIQUE(namespace_id, name)} makes this at most one row. Used by the gateway, which
     * addresses its skill by name — the name is part of the published URL — rather than by id,
     * because its id is not known until it has been published once.
     */
    public java.util.Optional<SkillRow> liveByName(String namespaceId, String name) {
        return jdbc.sql("""
                SELECT id, namespace_id, name, title, description, frontmatter, visibility,
                       current_version_id
                FROM skill
                WHERE namespace_id = :namespaceId AND name = :name AND deleted_at IS NULL
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query((rs, rowNum) -> new SkillRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("frontmatter"),
                        rs.getString("visibility"),
                        rs.getString("current_version_id")))
                .optional();
    }

    /** @return whether a live skill was deleted; false means there is no such live skill */
    public boolean softDelete(String skillId, String namespaceId, String at) {
        return jdbc.sql("""
                UPDATE skill SET deleted_at = :at, updated_at = :at
                WHERE id = :id AND namespace_id = :namespaceId AND deleted_at IS NULL
                """)
                .param("at", at)
                .param("id", skillId)
                .param("namespaceId", namespaceId)
                .update() == 1;
    }

    /**
     * @param currentVersionId nullable by column, never null in a live row: publishing creates the
     *                         skill and its first version in one transaction, so a live skill
     *                         without a current version is a broken invariant rather than a state
     */
    public record SkillRow(String id, String namespaceId, String name, String title,
            String description, String frontmatter, String visibility, String currentVersionId) {
    }
}
