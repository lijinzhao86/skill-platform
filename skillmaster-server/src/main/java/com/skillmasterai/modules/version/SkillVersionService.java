package com.skillmasterai.modules.version;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.version.internal.BlobGc;
import com.skillmasterai.modules.version.internal.SkillRepository;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.Optional;

/**
 * M7: the skill's identity, its immutable versions, and the pointer between them.
 *
 * <p><strong>This class never opens a transaction.</strong> §2.5 rule 2 puts cross-module
 * transaction boundaries in the use-case layer, and publishing is nothing but cross-module — so
 * every method here is written to be called inside a transaction the caller owns. Running one
 * outside a transaction would still work, and would be wrong: the version row, its files, and the
 * pointer would each commit separately.
 */
public final class SkillVersionService {

    private final SkillRepository skills;
    private final VersionRepository versions;
    private final BlobGc blobGc;

    public SkillVersionService(SkillRepository skills, VersionRepository versions, BlobGc blobGc) {
        this.skills = skills;
        this.versions = versions;
        this.blobGc = blobGc;
    }

    /**
     * Records a version of the named skill from an already-stored manifest.
     *
     * <p>The blobs are stored by the caller before this is called: M7 deals in {@link Manifest},
     * which carries digests and sizes, and never touches bytes. That keeps the {@link
     * com.skillmasterai.modules.blob.BlobStore} seam the only way to reach content.
     *
     * @throws SkillDeletedException if the name belongs to a soft-deleted skill
     */
    public PublishOutcome publish(String namespaceId, SkillMetadata metadata, Manifest manifest,
            String publishedBy, String source) {
        String at = Timestamps.now();

        String skillId = skills.upsertLive(namespaceId, metadata, publishedBy, at)
                .orElseThrow(() -> new SkillDeletedException(metadata.name()));

        Optional<String> inserted =
                versions.insertIfAbsent(skillId, manifest.digest(), manifest.fileCount(),
                        manifest.totalBytes(), source, publishedBy, at);

        if (inserted.isPresent()) {
            versions.insertFiles(inserted.get(), manifest);
            skills.moveCurrentVersion(skillId, inserted.get(), at);
        }

        // Read back rather than trusting the inputs: on a replay the authoritative published_at is
        // the original one, and a client comparing timestamps would otherwise see it move.
        VersionRepository.VersionRow row =
                versions.findBySkillAndDigest(skillId, manifest.digest())
                        .orElseThrow(() -> new IllegalStateException(
                                "version for digest " + manifest.digest() + " vanished mid-publish"));

        blobGc.sweep();

        return new PublishOutcome(skillId, row.digest(), row.fileCount(), row.totalBytes(),
                row.publishedAt(), inserted.isPresent());
    }

    /**
     * A live skill's full state, or empty if there is no such skill in that namespace.
     *
     * <p>The namespace is a required parameter rather than something the caller checks afterwards,
     * which is what makes an unreadable skill indistinguishable from an absent one: see
     * {@link SkillRepository#liveInNamespace}. The caller gets no way to ask "does it exist" —
     * only "is there one I may read".
     *
     * @param namespaceId the namespace the caller is allowed to read from
     */
    public Optional<SkillSnapshot> liveSnapshotInNamespace(String skillId, String namespaceId) {
        return skills.liveInNamespace(skillId, namespaceId).flatMap(this::toSnapshot);
    }

    /**
     * A live skill found by name rather than id, within one namespace.
     *
     * <p>For the gateway, whose skill is addressed by name because the name is part of its
     * published URL and its id is not known until after the first publish. The namespace is
     * required for the same reason as above — by-name lookups are otherwise a way to probe for
     * other people's skills.
     */
    public Optional<SkillSnapshot> liveSnapshotByName(String namespaceId, String name) {
        return skills.liveByName(namespaceId, name).flatMap(this::toSnapshot);
    }

    /**
     * Reads the version a skill row points at, and its manifest.
     *
     * <p>Loud rather than empty when the pointer dangles: publishing writes the skill and its first
     * version in one transaction, so a live skill with no current version is an invariant this
     * application cannot produce. Answering 404 would blame the caller for our own corruption.
     */
    private Optional<SkillSnapshot> toSnapshot(SkillRepository.SkillRow row) {
        if (row.currentVersionId() == null) {
            throw new IllegalStateException(
                    "skill " + row.id() + " is live but has no current version");
        }
        VersionRepository.VersionRow version = versions.findById(row.currentVersionId())
                .orElseThrow(() -> new IllegalStateException("skill " + row.id()
                        + " points at version " + row.currentVersionId() + ", which does not exist"));

        return Optional.of(new SkillSnapshot(
                row.id(), row.namespaceId(), row.name(), row.title(), row.description(),
                row.frontmatter(), row.visibility(), version.digest(), version.fileCount(),
                version.totalBytes(), version.publishedAt(), versions.filesOf(version.id())));
    }

    /** @return whether a live skill the given namespace owns was deleted */
    public boolean softDelete(String skillId, String namespaceId) {
        boolean deleted = skills.softDelete(skillId, namespaceId, Timestamps.now());
        blobGc.sweep();
        return deleted;
    }
}
