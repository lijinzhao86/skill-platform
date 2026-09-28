package com.skillmasterai.usecase.model;

/**
 * What the publish use case hands back.
 *
 * <p>Flattened into primitives rather than holding M7's {@code PublishOutcome}. The API layer is
 * allowed to see a module's public types, but a use case's result is the wrong place to make it
 * do so: this is the boundary the HTTP layer is written against, and keeping it free of module
 * types means a module can be rearranged without the controller noticing.
 *
 * @param created whether this call created the version; false means identical content was already
 *                published, and {@code publishedAt} is when it was first published
 */
public record PublishedSkill(
        String skillId,
        String name,
        String namespaceSlug,
        String digest,
        int fileCount,
        long totalBytes,
        String publishedAt,
        boolean created) {
}
