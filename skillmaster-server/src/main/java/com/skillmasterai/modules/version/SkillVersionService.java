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
        // Before anything writes version_file, and unconditionally — including the replay that
        // inserts nothing, which still sweeps. See BlobGc.beginExclusiveWrite.
        blobGc.beginExclusiveWrite();

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

        // Read back rather than trusting the inputs: on a replay the authoritative published_at and
        // number are the originals, so a client comparing either would otherwise see them move — and
        // a number taken from this call's own arithmetic would be one that was never assigned.
        VersionRepository.VersionRow row =
                versions.findBySkillAndDigest(skillId, manifest.digest())
                        .orElseThrow(() -> new IllegalStateException(
                                "version for digest " + manifest.digest() + " vanished mid-publish"));

        blobGc.sweep();

        return new PublishOutcome(skillId, row.number(), row.digest(), row.fileCount(),
                row.totalBytes(), row.publishedAt(), inserted.isPresent());
    }

    /**
     * One of a live skill's versions, or empty when there is no such skill in that namespace.
     *
     * <p>The namespace is a required parameter rather than something the caller checks afterwards,
     * which is what makes an unreadable skill indistinguishable from an absent one: see
     * {@link SkillRepository#liveByName}. The caller gets no way to ask "does it exist" — only "is
     * there one I may read".
     *
     * <p><strong>Two conditions, both checked.</strong> The skill must be live <em>and</em> the
     * version must exist. Resolving the version row alone would keep serving a soft-deleted skill,
     * because a soft delete leaves {@code skill_version} and {@code version_file} in place (§3.3
     * point 5) — the mistake ADR 0012's 后果 section names.
     *
     * @param namespaceId the namespace the caller is allowed to read from
     * @param pin         which version. {@link VersionPin.Latest} re-reads the pointer on every
     *                    call, so it drifts as soon as someone publishes; the other two never do
     */
    public Optional<SkillSnapshot> liveSnapshot(String namespaceId, String name, VersionPin pin) {
        return skills.liveByName(namespaceId, name)
                .flatMap(row -> versionOf(row, pin).map(version -> toSnapshot(row, version)));
    }

    /**
     * The version a pin selects, or empty when it names one that does not exist.
     *
     * <p>The two failure modes are deliberately not the same answer. A dangling pointer is our own
     * corruption — publishing writes the skill and its first version in one transaction, so a live
     * skill with no current version cannot come from this application — and it is loud, because
     * answering 404 would blame the caller for it. A pinned version that is absent, on the other
     * hand, is an ordinary miss: the address named something that was never published.
     */
    private Optional<VersionRepository.VersionRow> versionOf(SkillRepository.SkillRow row,
            VersionPin pin) {
        if (row.currentVersionId() == null) {
            throw new IllegalStateException(
                    "skill " + row.id() + " is live but has no current version");
        }
        return switch (pin) {
            case VersionPin.Latest() -> Optional.of(versions.findById(row.currentVersionId())
                    .orElseThrow(() -> new IllegalStateException("skill " + row.id()
                            + " points at version " + row.currentVersionId()
                            + ", which does not exist")));
            case VersionPin.Number(int number) -> versions.findBySkillAndNumber(row.id(), number);
            case VersionPin.Digest(String sha256Hex) ->
                    versions.findBySkillAndDigest(row.id(), sha256Hex);
        };
    }

    private SkillSnapshot toSnapshot(SkillRepository.SkillRow row,
            VersionRepository.VersionRow version) {
        return new SkillSnapshot(
                row.id(), row.namespaceId(), row.name(), row.title(), row.description(),
                row.frontmatter(), row.visibility(), version.number(), version.digest(),
                version.fileCount(), version.totalBytes(), version.publishedAt(),
                version.id().equals(row.currentVersionId()), versions.filesOf(version.id()));
    }

    /**
     * Soft-deletes a live skill by name, and reports which one it was.
     *
     * <p>The sweep runs unconditionally, exactly as it does on publish: §3.3 point 5 requires
     * reclamation to happen in the same transaction as the version change that caused it, and a soft
     * delete is such a change — even though in P0 it orphans nothing, because every version survives.
     *
     * @return the deleted skill's id, or empty when no live skill of that name is in that namespace
     */
    public Optional<String> softDelete(String namespaceId, String name) {
        // Taken first for the same reason as on publish, even though this path writes no
        // version_file row of its own: the sweep is here, and it has to be exclusive against a
        // concurrent publish rather than only against another delete. See BlobGc.beginExclusiveWrite.
        blobGc.beginExclusiveWrite();

        Optional<String> deleted = skills.softDelete(namespaceId, name, Timestamps.now());
        blobGc.sweep();
        return deleted;
    }
}
