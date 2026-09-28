package com.skillmasterai.modules.search;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.version.SkillCatalogService;
import java.util.List;
import java.util.Optional;

/**
 * M8: finding a skill by what it says about itself.
 *
 * <p>What is searched is fixed by §3.4 and is a safety constraint rather than a performance one:
 * the name, the title and the description, and <strong>never the body or the files</strong>. A
 * full-text index over a skill's content would let fragments of it be reconstructed by querying for
 * them, and the manifest's whole purpose is that content is fetched deliberately and completely
 * rather than sampled.
 *
 * <p><strong>This class owns policy and no SQL.</strong> Ranking, the meaning of a cursor, and the
 * decision to search at all are here; the query itself is M7's, because the tables are, and §2.5
 * rule 1 admits no arrangement where one statement names two modules' tables. The same split as the
 * blob sweep, for the same reason.
 *
 * <h2>Why the ordering is a tuple and not a score</h2>
 *
 * <p>Results come back ordered by {@code (relevance, updated_at descending, id)} — a lexicographic
 * comparison, not a single weighted sum. The sum is the more obvious design and it is wrong here,
 * for a reason that only appears on page two: a sum containing the current time evaluates
 * differently every time it runs, so the key a cursor carries stops meaning what it meant when the
 * cursor was issued, and rows get skipped or repeated with nothing in the response to say so.
 *
 * <p>A tuple has no such problem — every component is a stored column or a function of stored
 * columns — and it states the intent more directly: text relevance first, newest first within a
 * tier, and an id to break the remaining ties so the order is total.
 *
 * <p><strong>The namespace is a required parameter.</strong> §3.4 is blunt that the ownership
 * filter has to be in the query, because a search that returns an index's matches directly leaks
 * every other user's private skills. v1 has one namespace per user, so passing it makes the result
 * "mine" — and it is applied where the rows are found, not afterwards, so there is no version of
 * the query that can forget it.
 */
public final class SkillSearchService {

    private final SkillCatalogService catalog;
    private final RelevanceWeights weights;

    public SkillSearchService(SkillCatalogService catalog, RelevanceWeights weights) {
        this.catalog = catalog;
        this.weights = weights;
    }

    /** @param visible the namespace the caller may search; see the class note */
    public SearchPage search(SearchRequest request, Namespace visible) {
        boolean byRelevance = request.sort() == SearchRequest.SortOrder.RELEVANCE;
        String ordering = orderingIdOf(byRelevance);
        Optional<CursorCodec.Cursor> cursor =
                resumePointOf(request.cursor(), ordering, byRelevance);

        // One more row than asked for: its presence is how "there is another page" is known without
        // a second query to count what is left.
        List<SkillCatalogService.CatalogRow> rows = catalog.page(
                visible.id(),
                request.query(),
                new SkillCatalogService.RankingWeights(weights.name(), weights.title(),
                        weights.description()),
                byRelevance,
                cursor.map(CursorCodec.Cursor::key).orElse(null),
                request.limit() + 1);

        boolean hasMore = rows.size() > request.limit();
        List<SkillCard> cards = rows.stream()
                .limit(request.limit())
                .map(row -> toCard(row, visible))
                .toList();

        // The cursor names the last row actually returned, never the extra one fetched only to
        // answer "is there more" — a cursor pointing past a row would drop it.
        String next = hasMore
                ? CursorCodec.encode(ordering,
                        rows.get(request.limit() - 1).sortKey(byRelevance))
                : null;
        return new SearchPage(cards, next);
    }

    /** @param nextCursor null when this is the last page — the only signal a client needs */
    public record SearchPage(List<SkillCard> skills, String nextCursor) {
        public SearchPage {
            skills = List.copyOf(skills);
        }
    }

    private String orderingIdOf(boolean byRelevance) {
        return byRelevance ? weights.orderingId() : "recent";
    }

    /**
     * The cursor's key, once it is known to belong to this ordering.
     *
     * <p>Every rejection here is a 400 rather than a fresh first page. Silently ignoring a cursor a
     * client believes in turns pagination into a loop that returns page one forever, and the client
     * cannot tell that from a listing that happens to shrink.
     */
    private Optional<CursorCodec.Cursor> resumePointOf(String cursor, String ordering,
            boolean byRelevance) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor)
                .orElseThrow(() -> new InvalidSearchRequestException(
                        "the cursor is not one this version understands"));
        if (!decoded.ordering().equals(ordering)) {
            throw new InvalidSearchRequestException(
                    "the cursor belongs to a different ordering: it was issued for '"
                            + decoded.ordering() + "' and this request asks for '" + ordering + "'");
        }
        if (decoded.key().size() != catalog.sortKeyWidth(byRelevance)) {
            throw new InvalidSearchRequestException("the cursor has the wrong number of key parts");
        }
        return Optional.of(decoded);
    }

    /**
     * The namespace slug comes from the caller's namespace rather than from the row.
     *
     * <p>Not a shortcut: v1 returns skills from exactly one namespace — the caller's — so the slug
     * is the same for every card, and reading it from the row would mean M7 joining {@code
     * namespace}, a table it does not own, for a value already in hand.
     */
    private static SkillCard toCard(SkillCatalogService.CatalogRow row, Namespace visible) {
        return new SkillCard(row.id(), row.name(), row.title(), row.description(), visible.slug(),
                row.visibility(), row.digest(), row.updatedAt());
    }
}
