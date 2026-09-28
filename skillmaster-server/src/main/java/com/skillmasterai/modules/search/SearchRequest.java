package com.skillmasterai.modules.search;

/**
 * What a caller asked for, already validated.
 *
 * <p>Validated at construction rather than deep in the query, so that a bad request fails before
 * any SQL runs and the failure carries the parameter's name.
 *
 * @param query          the search text, or null for "everything, in the requested order". An
 *                       empty string means the same thing rather than a third behaviour: the
 *                       pattern builder turns both into no pattern at all, so {@code ?q=} and a
 *                       request with no {@code q} produce one query, not two.
 * @param namespaceSlug  narrows to one namespace <em>within</em> what the caller may see; it is not
 *                       a way to widen. §4.2 is explicit that it is a filter, not a bypass.
 * @param sort           {@code relevance} (the default) or {@code recent}
 * @param limit          rows to return, at most {@link #MAX_LIMIT}
 * @param cursor         the last row of the previous page, opaque; null for the first page
 */
public record SearchRequest(String query, String namespaceSlug, SortOrder sort, int limit,
        String cursor) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public SearchRequest {
        // A request for zero rows is a mistake rather than a smaller answer, and silently serving
        // the default would make it invisible. The upper bound is different: §4.2 states 100 as a
        // ceiling, so a larger request is clamped to it rather than refused.
        if (limit < 1) {
            throw new InvalidSearchRequestException("limit must be at least 1, was " + limit);
        }
        limit = Math.min(limit, MAX_LIMIT);
    }

    /**
     * {@code relevance} ranks by text and falls back to recency; {@code recent} ignores the text
     * entirely.
     *
     * <p>The wire names are lowercase and the constants are not, deliberately — the same rule as
     * {@link com.skillmasterai.common.ErrorCode}: the strings are the contract and the identifiers
     * are ours to rename. Spring's default enum conversion would have taken the constant name, so
     * {@code sort=RECENT} would have worked and the documented {@code sort=recent} would have been
     * a 500; parsing here is what keeps the published spelling the real one.
     */
    public enum SortOrder {
        RELEVANCE("relevance"),
        RECENT("recent");

        private final String wireName;

        SortOrder(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /**
         * @throws InvalidSearchRequestException for anything else, which is the caller's to fix and
         *         therefore a 400 rather than a default silently applied
         */
        public static SortOrder fromWire(String value) {
            for (SortOrder order : values()) {
                if (order.wireName.equals(value)) {
                    return order;
                }
            }
            throw new InvalidSearchRequestException(
                    "sort must be 'relevance' or 'recent', was '" + value + "'");
        }
    }
}
