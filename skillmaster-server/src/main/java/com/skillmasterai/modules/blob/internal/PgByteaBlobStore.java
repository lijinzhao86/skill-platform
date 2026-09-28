package com.skillmasterai.modules.blob.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.blob.BlobNotFoundException;
import com.skillmasterai.modules.blob.BlobStore;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bytes in PostgreSQL, in the {@code blob_content} table (ADR 0010 decision 2).
 *
 * <p>Public but confined: it lives in {@code internal}, and the architecture test forbids any
 * package outside this module from referencing {@code internal}. Public rather than
 * package-private because a module's wiring class sits in the parent package and could not
 * otherwise construct it — package-private does not cross a package boundary, and a subpackage is
 * a boundary. The guarantee that matters ("nothing outside this module knows what the backend is")
 * is the architecture rule's, not javac's.
 *
 * <p>Both writes are {@code ON CONFLICT DO NOTHING} rather than a read-then-write: identical
 * content is the normal case (every republish, and any shared file between two skills), and a
 * check-then-insert would race between concurrent publishes of the same content.
 */
public final class PgByteaBlobStore implements BlobStore {

    private final JdbcClient jdbc;

    public PgByteaBlobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BlobRef put(byte[] bytes) {
        String sha256 = Sha256Hex.of(bytes);

        // Metadata first: blob_content.sha256 references blob.sha256, and the digest is computed
        // here rather than accepted from the caller, so the two can never disagree.
        jdbc.sql("""
                INSERT INTO blob (sha256, size, created_at)
                VALUES (:sha256, :size, :createdAt)
                ON CONFLICT (sha256) DO NOTHING
                """)
                .param("sha256", sha256)
                .param("size", bytes.length)
                .param("createdAt", Timestamps.now())
                .update();

        jdbc.sql("""
                INSERT INTO blob_content (sha256, bytes)
                VALUES (:sha256, :bytes)
                ON CONFLICT (sha256) DO NOTHING
                """)
                .param("sha256", sha256)
                .param("bytes", bytes)
                .update();

        return new BlobRef(sha256, bytes.length);
    }

    @Override
    public byte[] get(String sha256Hex) {
        return jdbc.sql("SELECT bytes FROM blob_content WHERE sha256 = :sha256")
                .param("sha256", sha256Hex)
                .query((rs, rowNum) -> rs.getBytes("bytes"))
                .optional()
                .orElseThrow(() -> new BlobNotFoundException(sha256Hex));
    }

    @Override
    public boolean exists(String sha256Hex) {
        return jdbc.sql("SELECT 1 FROM blob WHERE sha256 = :sha256")
                .param("sha256", sha256Hex)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Deletes the metadata row; {@code blob_content.sha256} cascades from it, so the bytes go too.
     *
     * <p>Only {@code blob} is named here on purpose — this is M6's table, and the set of digests
     * still in use arrives as a parameter rather than as a subquery over M7's {@code version_file}.
     *
     * <p>{@code <> ALL} over an empty array is true for every row, which is what makes "nothing is
     * referenced" behave as "delete everything" without a branch.
     */
    @Override
    public int deleteUnreferenced(Set<String> referenced) {
        return jdbc.sql("DELETE FROM blob WHERE sha256 <> ALL (:referenced)")
                .param("referenced", referenced.toArray(String[]::new))
                .update();
    }
}
