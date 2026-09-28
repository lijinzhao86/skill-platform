package com.skillmasterai.modules.version;

/**
 * Everything M7 knows about one live skill: its identity, its metadata, and its current version
 * with the full manifest.
 *
 * <p>One object rather than three lookups at the call site, because the three are only meaningful
 * together — a version without its skill has no name to serve, and a skill without its version has
 * no content. Assembling it is M7's job; what to do with it is not.
 *
 * <p>{@code frontmatterJson} stays a string here. M7 has no opinion about the shape of a
 * frontmatter document — it stores what M5 parsed and hands it back — and turning it into a tree
 * is the API layer's business, which is also what keeps unknown fields passing through untouched
 * (§3.3).
 *
 * @param manifest the current version's files, sorted by relpath, carrying no content
 */
public record SkillSnapshot(
        String skillId,
        String namespaceId,
        String name,
        String title,
        String description,
        String frontmatterJson,
        String visibility,
        String digest,
        int fileCount,
        long totalBytes,
        String publishedAt,
        Manifest manifest) {
}
