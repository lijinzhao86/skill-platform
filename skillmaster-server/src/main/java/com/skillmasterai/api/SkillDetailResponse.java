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
 *   <li><strong>{@code resources} is built from path templates.</strong> §4.2 shows them with
 *       literal {@code {id}} and {@code {relpath}} placeholders for the client to substitute. They
 *       are not filled in, because a URL per file would grow the manifest with every file and the
 *       manifest's job is to be small enough to read without fetching anything.</li>
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

    public record Version(String digest, String publishedAt, int fileCount, long totalBytes) {
    }

    public record File(String relpath, String sha256, long size, boolean isBinary) {
    }

    public record Resources(String body, String file) {
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
                        // The stored digest is bare lowercase hex; the API presents it prefixed,
                        // as §4.2 shows.
                        prefixed(detail.digest()),
                        detail.publishedAt(),
                        detail.fileCount(),
                        detail.totalBytes()),
                detail.files().stream()
                        .map(file -> new File(file.relpath(), prefixed(file.sha256Hex()),
                                file.size(), file.isBinary()))
                        .toList(),
                new Resources("/v1/skills/{id}/body", "/v1/skills/{id}/files/{relpath}"));
    }

    private static String prefixed(String sha256Hex) {
        return "sha256:" + sha256Hex;
    }
}
