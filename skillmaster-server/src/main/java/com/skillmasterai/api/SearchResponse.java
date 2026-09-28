package com.skillmasterai.api;

import com.skillmasterai.modules.search.SkillSearchService;
import java.util.List;

/**
 * §4.2's listing body: L1 fields and a cursor.
 *
 * <p>{@code next_cursor} is the whole pagination contract — present means there is another page,
 * absent means this was the last. No total count accompanies it, because a count over a table
 * being written to is stale the moment it is computed, and a client that branches on it will one
 * day fetch a page that is not there.
 */
public record SearchResponse(List<Card> skills, String nextCursor) {

    public record Card(
            String id,
            String name,
            String title,
            String description,
            String namespace,
            String visibility,
            String digest,
            String updatedAt) {
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
                                // Storage keeps the bare hex ADR 0005's formula produces; the API
                                // presents it prefixed, as §4.2 shows.
                                "sha256:" + card.digest(),
                                card.updatedAt()))
                        .toList(),
                page.nextCursor());
    }
}
