package com.skillmasterai.api;

import com.skillmasterai.modules.search.SkillSearchService;
import java.util.List;

/**
 * §4.2's listing body: L1 fields and a cursor.
 *
 * <p>{@code next_cursor} is the whole pagination contract — a value means there is another page,
 * and {@code null} means this was the last. It is written as an explicit {@code null} rather than
 * omitted, which is what §4.2's example shows; the two say the same thing to a client and only one
 * of them can be documented. No total count accompanies it, because a count over a table being
 * written to is stale the moment it is computed, and a client that branches on it will one day
 * fetch a page that is not there.
 */
public record SearchResponse(List<Card> skills, String nextCursor) {

    public record Card(
            String id,
            String name,
            String title,
            String description,
            String namespace,
            String visibility,
            Version version,
            String updatedAt) {
    }

    /**
     * The current version, as a card reports it.
     *
     * <p>Both halves are here because they answer different questions: the number is the short thing
     * a client carries forward in an address, and the digest is the identity it can verify content
     * against. Having both on the card is what lets one search be enough to pin.
     */
    public record Version(int number, String digest) {
    }

    public static SearchResponse of(SkillSearchService.SearchPage page) {
        return new SearchResponse(
                page.skills().stream()
                        .map(card -> new Card(
                                card.id(),
                                card.name(),
                                card.title(),
                                card.description(),
                                card.namespaceSlug(),
                                card.visibility(),
                                new Version(
                                        card.number(),
                                        // Storage keeps the bare hex ADR 0005's formula produces;
                                        // the API presents it prefixed, as §4.2 shows.
                                        "sha256:" + card.digest()),
                                card.updatedAt()))
                        .toList(),
                page.nextCursor());
    }
}
