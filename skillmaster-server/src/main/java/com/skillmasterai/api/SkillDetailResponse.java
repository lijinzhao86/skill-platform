package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.SkillDetail;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * §4.2's detail body.
 *
 * <p>Two things here are not plain copies of {@link SkillDetail}, and both are about the wire
 * rather than the data:
 *
 * <ul>
 *   <li><strong>{@code frontmatter} is a JSON object, not a string.</strong> It is stored as a
 *       string because M7 has no opinion about its shape; a client should not have to parse a
 *       string-inside-JSON to read it. Parsing it here is also what makes §3.3's pass-through rule
 *       visible: whatever was in the frontmatter comes out, nested mappings and unrecognised keys
 *       included, because nothing between M5 and here ever enumerated the fields.</li>
 *   <li><strong>Every file carries a {@code uri}, and {@code resources} carries the body's.</strong>
 *       These are the pinning mechanism of §4.2 and ADR 0012: literal addresses with the resolved
 *       version already written into them, so a client that follows them never asks for
 *       {@code latest} again — and therefore cannot have the content swapped underneath it by an
 *       intervening publish. There is deliberately no {@code {relpath}} template beside them: two
 *       ways to spell one URL is a second source of truth, and the manifest's job is to leave
 *       nothing for the client to assemble.</li>
 * </ul>
 */
public record SkillDetailResponse(
        String id,
        String name,
        String title,
        String description,
        Namespace namespace,
        String visibility,
        JsonNode frontmatter,
        Version version,
        List<File> files,
        Resources resources) {

    public record Namespace(String slug, String title) {
    }

    /**
     * @param isLatest whether the version reported is the one the skill currently points at — false
     *                 whenever the address pinned an older one, which is how a client learns that
     *                 what it is reading is not what it would get from the bare address
     */
    public record Version(int number, String digest, String publishedAt, int fileCount,
            long totalBytes, boolean isLatest) {
    }

    /** @param uri the file's pinned address, to be fetched verbatim rather than assembled */
    public record File(String relpath, String uri, String sha256, long size, boolean isBinary) {
    }

    /** @param body the pinned address of the {@code SKILL.md} bytes */
    public record Resources(String body) {
    }

    public static SkillDetailResponse of(SkillDetail detail, ObjectMapper objectMapper) {
        return new SkillDetailResponse(
                detail.id(),
                detail.name(),
                detail.title(),
                detail.description(),
                new Namespace(detail.namespaceSlug(), detail.namespaceTitle()),
                detail.visibility(),
                objectMapper.readTree(detail.frontmatterJson()),
                new Version(
                        detail.number(),
                        // The stored digest is bare lowercase hex; the API presents it prefixed,
                        // as §4.2 shows.
                        prefixed(detail.digest()),
                        detail.publishedAt(),
                        detail.fileCount(),
                        detail.totalBytes(),
                        detail.isLatest()),
                detail.files().stream()
                        .map(file -> new File(
                                file.relpath(),
                                // Pinned by number rather than by digest: equally immutable, and the
                                // shorter thing for a client to carry through a task.
                                SkillRoutes.fileAt(detail.namespaceSlug(), detail.name(),
                                        detail.number(), file.relpath()),
                                prefixed(file.sha256Hex()),
                                file.size(),
                                file.isBinary()))
                        .toList(),
                new Resources(SkillRoutes.bodyAt(detail.namespaceSlug(), detail.name(),
                        detail.number())));
    }

    private static String prefixed(String sha256Hex) {
        return "sha256:" + sha256Hex;
    }
}
