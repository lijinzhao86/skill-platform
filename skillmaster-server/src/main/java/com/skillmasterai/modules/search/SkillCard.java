package com.skillmasterai.modules.search;

/**
 * One row of §4.2's search result: L1, and nothing else.
 *
 * <p>No file list, no body, no content. That is the first gate of progressive loading (§4.2), and
 * it is why {@code description} is here at all — the whole point of a listing is that an agent can
 * judge relevance from it without fetching anything.
 *
 * @param number    the current version's immutable alias (ADR 0012). Carried on the card so that one
 *                  search is enough to pin: a client can address {@code @3} or {@code @sha256:…}
 *                  without fetching {@code latest} first
 * @param digest    the current version's digest, bare lowercase hex; the API adds the prefix
 * @param updatedAt RFC3339 UTC. Unlike the version fields it is not the version's — a metadata edit
 *                  moves this without creating a version (§4.3), and this is the field the ordering
 *                  sorts on
 */
public record SkillCard(
        String id,
        String name,
        String title,
        String description,
        String namespaceSlug,
        String visibility,
        int number,
        String digest,
        String updatedAt) {
}
