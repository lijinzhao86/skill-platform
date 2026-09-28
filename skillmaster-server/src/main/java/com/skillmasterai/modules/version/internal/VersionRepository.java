package com.skillmasterai.modules.version.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill_version} and {@code version_file} — M7's other two tables. */
public final class VersionRepository {

    private final JdbcClient jdbc;

    public VersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the version unless the same content is already published under this skill.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique violation, for a reason
     * specific to PostgreSQL: an error there aborts the entire transaction, so a caught violation
     * would leave the publish unable to write anything else. {@code DO NOTHING} also waits on a
     * concurrent uncommitted insert of the same digest and then correctly finds the committed row.
     * This is ADR 0005's "幂等由唯一约束直接实现，不需要先查再写" made concrete.
     *
     * <p><strong>The number is allocated here, but its serialisation comes from the caller.</strong>
     * {@code MAX(number) + 1} in the statement below is safe only because the publish path takes the
     * {@code skill} row's lock first: {@link SkillRepository#upsertLive}'s {@code ON CONFLICT … DO
     * UPDATE} locks that row before its {@code WHERE} is even evaluated, so the lock is held even
     * when the update is filtered out. Two concurrent publishes of one skill therefore queue on it
     * and cannot interleave. That is an <em>unenforced</em> convention — a second writer that skips
     * {@code upsertLive}, or a raised isolation level (the subquery would read a stale snapshot),
     * breaks it. It breaks loudly rather than quietly: whatever the failure, it arrives as a SQL
     * error rather than as two contents sharing a number.
     *
     * <p>Deliberately <em>not</em> {@code ON CONFLICT (skill_id, number) DO NOTHING}: that would turn
     * a broken invariant into silent aliasing — two contents sharing one number, which is exactly the
     * property ADR 0012 says {@code @3} must never lose. A constraint violation here is the correct
     * alarm, so it is left to surface as one.
     *
     * <p>And deliberately not a sequence: {@code MAX(number) + 1} leaves no gap when a transaction
     * rolls back, whereas a sequence is non-transactional and would burn numbers and hand them out
     * out of order. Consuming nothing on the conflict branch is the same property, one case over.
     *
     * @return the new version's id, or empty when identical content was already published
     */
    public Optional<String> insertIfAbsent(String skillId, String digest, int fileCount,
            long totalBytes, String source, String publishedBy, String at) {
        return jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,
                                           changelog, source, published_by, published_at)
                VALUES (:id, :skillId,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version
                         WHERE skill_id = :skillId),
                        :digest, :fileCount, :totalBytes,
                        '', :source, :publishedBy, :at)
                ON CONFLICT (skill_id, digest) DO NOTHING
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("skillId", skillId)
                .param("digest", digest)
                .param("fileCount", fileCount)
                .param("totalBytes", totalBytes)
                .param("source", source)
                .param("publishedBy", publishedBy)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * Writes the manifest, one row per file.
     *
     * <p>A statement per file rather than a set-based insert. With the ceiling at 512 files, inside
     * one transaction, on a local socket, that is a few milliseconds — and publishing is a rare
     * human action, not a hot path. Worth revisiting only if publish latency ever shows up.
     */
    public void insertFiles(String versionId, Manifest manifest) {
        for (ManifestEntry entry : manifest.entries()) {
            jdbc.sql("""
                    INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                    VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary)
                    """)
                    .param("versionId", versionId)
                    .param("relpath", entry.relpath())
                    .param("blobSha256", entry.blobSha256())
                    .param("size", entry.size())
                    .param("isBinary", entry.isBinary() ? 1 : 0)
                    .update();
        }
    }

    /** The authoritative row for a published version, however it got there. */
    public Optional<VersionRow> findBySkillAndDigest(String skillId, String digest) {
        return jdbc.sql("""
                SELECT id, number, digest, file_count, total_bytes, published_at
                FROM skill_version WHERE skill_id = :skillId AND digest = :digest
                """)
                .param("skillId", skillId)
                .param("digest", digest)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getString("id"),
                        rs.getInt("number"),
                        rs.getString("digest"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("published_at")))
                .optional();
    }

    /**
     * A version by its immutable alias — §4.1's {@code @3}.
     *
     * <p>A number is not an identity (the digest is), but {@code UNIQUE (skill_id, number)} makes it
     * a stable name for one piece of content, which is what an address needs. It is resolved through
     * the skill rather than globally because numbers are per-skill: {@code @3} means the third
     * version of <em>this</em> skill, and {@code @3} of another skill is unrelated content.
     */
    public Optional<VersionRow> findBySkillAndNumber(String skillId, int number) {
        return jdbc.sql("""
                SELECT id, number, digest, file_count, total_bytes, published_at
                FROM skill_version WHERE skill_id = :skillId AND number = :number
                """)
                .param("skillId", skillId)
                .param("number", number)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getString("id"),
                        rs.getInt("number"),
                        rs.getString("digest"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("published_at")))
                .optional();
    }

    public Optional<VersionRow> findById(String versionId) {
        return jdbc.sql("""
                SELECT id, number, digest, file_count, total_bytes, published_at
                FROM skill_version WHERE id = :id
                """)
                .param("id", versionId)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getString("id"),
                        rs.getInt("number"),
                        rs.getString("digest"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("published_at")))
                .optional();
    }

    /**
     * A version's manifest.
     *
     * <p>No {@code ORDER BY}: {@link Manifest} sorts explicitly, and it is the only thing that may
     * — the digest and the served manifest have to agree, and the database's collation is one of
     * the three orders that would disagree.
     */
    public Manifest filesOf(String versionId) {
        List<ManifestEntry> entries = jdbc.sql("""
                SELECT relpath, blob_sha256, size, is_binary
                FROM version_file WHERE version_id = :versionId
                """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new ManifestEntry(
                        rs.getString("relpath"),
                        rs.getString("blob_sha256"),
                        rs.getLong("size"),
                        rs.getInt("is_binary") != 0))
                .list();
        return Manifest.of(entries);
    }

    public record VersionRow(String id, int number, String digest, int fileCount, long totalBytes,
            String publishedAt) {
    }
}
