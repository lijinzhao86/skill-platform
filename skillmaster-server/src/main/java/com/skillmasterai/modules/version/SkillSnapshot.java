package com.skillmasterai.modules.version;

/**
 * Everything M7 knows about one live skill: its identity, its metadata, and one of its versions
 * with the full manifest.
 *
 * <p>One object rather than three lookups at the call site, because the three are only meaningful
 * together — a version without its skill has no name to serve, and a skill without its version has
 * no content. Assembling it is M7's job; what to do with it is not.
 *
 * <p><strong>Which version this is depends on the {@link VersionPin} that was asked for</strong>, so
 * it is not necessarily the current one — that is the whole point of pinning, and {@code isLatest}
 * says which case this is. Both facts are M7's to derive, because {@code current_version_id} is a
 * column of a table M7 owns.
 *
 * <p>{@code frontmatterJson} stays a string here. M7 has no opinion about the shape of a
 * frontmatter document — it stores what M5 parsed and hands it back — and turning it into a tree
 * is the API layer's business, which is also what keeps unknown fields passing through untouched
 * (§3.3).
 *
 * @param number   the version's immutable alias (ADR 0012) — what {@code @N} in an address names
 * @param isLatest whether this is the version the skill currently points at. Derived here rather
 *                 than compared at the edge, so that the pointer is read from one place
 * @param manifest that version's files, sorted by relpath, carrying no content
 */
public record SkillSnapshot(
        String skillId,
        String namespaceId,
        String name,
        String title,
        String description,
        String frontmatterJson,
        String visibility,
        int number,
        String digest,
        int fileCount,
        long totalBytes,
        String publishedAt,
        boolean isLatest,
        Manifest manifest) {
}
