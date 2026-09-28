package com.skillmasterai.modules.version;

/**
 * What a publish produced.
 *
 * @param number  the skill's Nth distinct content (ADR 0012). Read back from the stored row, not
 *                computed by the caller: on a replay this is the original number, and a number that
 *                was never assigned would be a lie about an address that works.
 * @param created whether this call created the version. False means identical content was already
 *                published and the unique constraint did its job (ADR 0005) — the version is the
 *                existing one, and its {@code publishedAt} is when it was first published, not now.
 *                Callers render this distinction as 201 versus 200.
 */
public record PublishOutcome(
        String skillId,
        int number,
        String digest,
        int fileCount,
        long totalBytes,
        String publishedAt,
        boolean created) {
}
