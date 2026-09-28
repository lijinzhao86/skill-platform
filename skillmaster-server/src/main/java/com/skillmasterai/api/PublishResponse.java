package com.skillmasterai.api;

import com.skillmasterai.usecase.model.PublishedSkill;

/**
 * The body of a successful publish.
 *
 * <p>The {@code version} object is §4.2's detail shape, so a client deserialising it once can read
 * it wherever it appears. Field names come out in snake_case — that is the documented wire format,
 * set globally in configuration rather than annotated here field by field.
 *
 * @param created false when identical content was already published. The response is still a
 *                success — idempotence is the feature (ADR 0005) — and the status differs (200
 *                versus 201) so a client can tell a first publish from a replay without comparing
 *                digests itself.
 */
public record PublishResponse(
        String id,
        String name,
        String namespace,
        boolean created,
        Version version) {

    public record Version(String digest, int fileCount, long totalBytes, String publishedAt) {
    }

    public static PublishResponse of(PublishedSkill published) {
        return new PublishResponse(
                published.skillId(),
                published.name(),
                published.namespaceSlug(),
                published.created(),
                new Version(
                        // The stored digest is bare lowercase hex; the API presents it prefixed, as
                        // §4.2 shows. Storage follows ADR 0005's formula literally and the prefix is
                        // a presentation concern.
                        "sha256:" + published.digest(),
                        published.fileCount(),
                        published.totalBytes(),
                        published.publishedAt()));
    }
}
