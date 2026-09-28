package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.blob.BlobStore;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reclaims blobs nothing references any more — the half of it that M7 owns.
 *
 * <p>Reference counting is M7's: {@code version_file} is what references a blob, and M7 owns that
 * table (§2.5). The rows being deleted belong to M6, so this class only decides <em>what</em> is
 * still in use and hands that set to {@link BlobStore#deleteUnreferenced}. One SQL statement
 * naming both modules' tables would be the rule-1 violation this split exists to avoid.
 *
 * <p><strong>In P0 this deletes nothing, and that is the correct behaviour, not an oversight.</strong>
 * Blobs are only ever orphaned by a version disappearing, and no P0 operation removes a
 * {@code skill_version} row: publishing adds one, republishing identical content is a no-op, and
 * soft delete only sets {@code deleted_at} while leaving every version — and therefore every
 * reference — in place (§3.3 point 5). The sweep is called on every version change so that the
 * transaction boundary §3.3 point 5 demands (ADR 0010 decision 4: reclamation and version change
 * in one transaction, never a cross-system reconciliation) is established now rather than
 * retrofitted, and so P2's version pruning has somewhere to land.
 *
 * <p>Worth knowing before P2 leans on this: the referenced set is every file of every version that
 * has ever been kept, so a repository with a large history passes a large set through the seam on
 * every publish. That is fine at P0's scale and is an open question for whenever version pruning
 * arrives.
 */
public final class BlobGc {

    private final JdbcClient jdbc;
    private final BlobStore blobs;

    public BlobGc(JdbcClient jdbc, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /** @return how many blobs were reclaimed */
    public int sweep() {
        Set<String> referenced = Set.copyOf(
                jdbc.sql("SELECT DISTINCT blob_sha256 FROM version_file").query(String.class).list());
        return blobs.deleteUnreferenced(referenced);
    }
}
