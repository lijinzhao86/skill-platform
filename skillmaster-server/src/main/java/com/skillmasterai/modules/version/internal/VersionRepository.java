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
     * @return the new version's id, or empty when identical content was already published
     */
    public Optional<String> insertIfAbsent(String skillId, String digest, int fileCount,
            long totalBytes, String source, String publishedBy, String at) {
        return jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, digest, file_count, total_bytes,
                                           changelog, source, published_by, published_at)
                VALUES (:id, :skillId, :digest, :fileCount, :totalBytes,
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
                SELECT id, digest, file_count, total_bytes, published_at
                FROM skill_version WHERE skill_id = :skillId AND digest = :digest
                """)
                .param("skillId", skillId)
                .param("digest", digest)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getString("id"),
                        rs.getString("digest"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("published_at")))
                .optional();
    }

    public Optional<VersionRow> findById(String versionId) {
        return jdbc.sql("""
                SELECT id, digest, file_count, total_bytes, published_at
                FROM skill_version WHERE id = :id
                """)
                .param("id", versionId)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getString("id"),
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

    public record VersionRow(String id, String digest, int fileCount, long totalBytes,
            String publishedAt) {
    }
}
