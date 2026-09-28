package com.skillmasterai.modules.distribution;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.Optional;

/**
 * M9: serving a skill at its three levels of verbosity.
 *
 * <p>L1 is the listing (M8), L2 the body, L3 a single file — and this class serves L1's detail
 * shape plus L2 and L3. All three are the same question with a different answer size, which is why
 * they share one resolver: {@link #readableIn} is the only place a skill is turned into something a
 * caller may look at.
 *
 * <p><strong>Content is fetched by digest, never by path.</strong> L3 looks a {@code relpath} up in
 * the stored manifest and reads the blob the manifest names. Nothing here resolves a path, joins a
 * path, or touches a filesystem — §4.2 requires the exact-match rule, and obeying it by using a
 * lookup rather than a sanitiser is the difference between a rule that cannot be broken and one
 * that has to be remembered.
 */
public final class SkillDistributionService {

    /** The body a skill is required to have; M5 refuses an upload without one (§1.3). */
    private static final String BODY_RELPATH = "SKILL.md";

    private final SkillVersionService versions;
    private final BlobStore blobs;

    public SkillDistributionService(SkillVersionService versions, BlobStore blobs) {
        this.versions = versions;
        this.blobs = blobs;
    }

    /** @param namespace the namespace the caller may read from */
    public Optional<SkillDetail> detailOf(String skillId, Namespace namespace) {
        return readableIn(skillId, namespace).map(snapshot -> new SkillDetail(
                snapshot.skillId(),
                snapshot.name(),
                snapshot.title(),
                snapshot.description(),
                namespace.slug(),
                namespace.title(),
                snapshot.visibility(),
                snapshot.frontmatterJson(),
                snapshot.digest(),
                snapshot.publishedAt(),
                snapshot.fileCount(),
                snapshot.totalBytes(),
                snapshot.manifest().entries().stream()
                        .map(entry -> new SkillDetail.File(
                                entry.relpath(), entry.blobSha256(), entry.size(), entry.isBinary()))
                        .toList()));
    }

    /**
     * L2: the original {@code SKILL.md} bytes, frontmatter included.
     *
     * <p>Byte for byte what was uploaded. §4.2's "托管要保真" and ADR 0005's digest both depend on
     * this path never rewriting anything — no BOM stripping, no line-ending normalisation, no
     * re-encoding. The bytes served are the bytes whose digest the manifest advertises.
     */
    public Optional<byte[]> bodyOf(String skillId, Namespace namespace) {
        return readableIn(skillId, namespace).map(snapshot -> blobs.get(
                entryFor(snapshot, BODY_RELPATH).blobSha256()));
    }

    /** L3: one file's original bytes, chosen by exact {@code relpath} match. */
    public Optional<StoredFile> fileOf(String skillId, String relpath, Namespace namespace) {
        return readableIn(skillId, namespace)
                .flatMap(snapshot -> snapshot.manifest().find(relpath)
                        .map(entry -> new StoredFile(entry, blobs.get(entry.blobSha256()))));
    }

    /**
     * Resolves a skill the caller may read, or nothing.
     *
     * <p>Handing the namespace to M7 rather than checking its answer afterwards is what makes
     * "not yours" and "no such skill" a single outcome — see
     * {@link SkillVersionService#liveSnapshotInNamespace}.
     */
    private Optional<SkillSnapshot> readableIn(String skillId, Namespace namespace) {
        return versions.liveSnapshotInNamespace(skillId, namespace.id());
    }

    private static ManifestEntry entryFor(SkillSnapshot snapshot, String relpath) {
        return snapshot.manifest().find(relpath).orElseThrow(() ->
                // M5 requires SKILL.md at the skill root, so every published version has one. Its
                // absence means the manifest was written by something other than the publish path,
                // and inventing a 404 for it would hide that.
                new IllegalStateException("version " + snapshot.digest()
                        + " of skill " + snapshot.skillId() + " has no " + relpath));
    }

    /** A file's bytes together with the entry that named them. */
    public record StoredFile(ManifestEntry entry, byte[] bytes) {
    }
}
