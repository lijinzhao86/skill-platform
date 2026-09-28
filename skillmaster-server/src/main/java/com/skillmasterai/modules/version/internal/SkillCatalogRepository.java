package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.version.SkillCatalogService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pages {@code skill}, ordered and filtered.
 *
 * <p>Names only M7's own tables. The ownership filter is {@code skill.namespace_id = :namespaceId}
 * — a column of this module's table, so §3.4's requirement that the filter live in the query costs
 * nothing structurally: there is no join to another module for it, and therefore no way to write
 * the query without it.
 *
 * <p>The relevance score is computed here rather than in the caller because it has to be, for the
 * keyset to work: the cursor carries the score of the last row returned, so the next page's
 * predicate compares against a value this query produced. Handing the ranking to the caller would
 * mean re-computing it in a second language and hoping the two agreed.
 *
 * <p>{@code updated_at} is compared as text. Safe only because
 * {@link com.skillmasterai.common.Timestamps} writes RFC3339 UTC truncated to seconds: fixed
 * width, fixed offset, zero padded, so lexicographic order <em>is</em> chronological order.
 */
public final class SkillCatalogRepository {

    private static final String PATTERN = ":pattern " + LikePattern.ESCAPE_CLAUSE;

    /** What each field is worth — scoring, and deliberately not what decides inclusion. */
    private static final String RELEVANCE = """
            ( CASE WHEN s.name        ILIKE %1$s THEN :weightName        ELSE 0 END
            + CASE WHEN s.title       ILIKE %1$s THEN :weightTitle       ELSE 0 END
            + CASE WHEN s.description ILIKE %1$s THEN :weightDescription ELSE 0 END )
            """.formatted(PATTERN);

    /**
     * Which rows the text query admits.
     *
     * <p>Separate from {@link #RELEVANCE} because they answer different questions — this one says
     * whether a row is a result at all, that one says how good a result it is — and because
     * conflating them is a mistake that hides well: the first version of this query used the
     * pattern only in the score, so every search returned every skill, ranked. It passed the tests
     * that existed, because each of them happened to insert only matching rows.
     *
     * <p>A null pattern admits everything, which is §4.2's "empty q returns by sort".
     */
    private static final String TEXT_FILTER = """
            ( CAST(:pattern AS text) IS NULL
              OR s.name        ILIKE %s
              OR s.title       ILIKE %s
              OR s.description ILIKE %s )
            """.formatted(PATTERN, PATTERN, PATTERN);

    private final JdbcClient jdbc;

    public SkillCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SkillCatalogService.CatalogRow> page(String namespaceId, String queryText,
            SkillCatalogService.RankingWeights weights, boolean byRelevance, List<String> afterKey,
            int limit) {

        String sql = """
                SELECT t.id, t.name, t.title, t.description, t.visibility, t.digest,
                       t.updated_at, t.relevance
                FROM (
                    SELECT s.id, s.name, s.title, s.description, s.visibility, s.updated_at,
                           v.digest AS digest, %s AS relevance
                    FROM skill s
                    -- Inner, not left: publishing writes a skill and its first version in one
                    -- transaction, so a live skill always has a current version, and this join is
                    -- the only integrity check there is — current_version_id carries no foreign key
                    -- by design, to avoid a circular one. The detail path reports a break loudly;
                    -- a listing simply cannot represent one.
                    JOIN skill_version v ON v.id = s.current_version_id
                    WHERE s.deleted_at IS NULL
                      AND s.namespace_id = :namespaceId
                      AND %s
                ) t
                WHERE :hasCursor = FALSE OR %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(RELEVANCE, TEXT_FILTER, keyset(byRelevance), order(byRelevance));

        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("namespaceId", namespaceId)
                // A null pattern makes each ILIKE null, and CASE WHEN null takes the ELSE branch —
                // so "no text" scores zero for every row without a branch in the SQL. The cast is
                // not decoration: a bare null parameter leaves PostgreSQL unable to infer a type
                // on either side of ILIKE, and it would fail on the one path nobody exercises
                // until a client browses without typing anything.
                .param("pattern", LikePattern.containing(queryText))
                .param("weightName", weights.name())
                .param("weightTitle", weights.title())
                .param("weightDescription", weights.description())
                .param("hasCursor", afterKey != null)
                .param("limit", limit);

        List<String> key = afterKey != null ? afterKey : blankKey(byRelevance);
        for (int i = 0; i < key.size(); i++) {
            statement = statement.param("k" + i, key.get(i));
        }

        return statement.query((rs, rowNum) -> new SkillCatalogService.CatalogRow(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("visibility"),
                rs.getString("digest"),
                rs.getString("updated_at"),
                rs.getInt("relevance"))).list();
    }

    /**
     * Everything strictly after the row the cursor names.
     *
     * <p>Strict, because that row has already been returned and {@code <=} would repeat it. Total,
     * because {@code id} is the last component, so rows that tie on everything else still have a
     * defined order and the boundary falls between them rather than through them.
     */
    private static String keyset(boolean byRelevance) {
        return byRelevance
                ? "(t.relevance < CAST(:k0 AS integer)"
                        + " OR (t.relevance = CAST(:k0 AS integer) AND t.updated_at < :k1)"
                        + " OR (t.relevance = CAST(:k0 AS integer) AND t.updated_at = :k1"
                        + "     AND t.id > :k2))"
                : "(t.updated_at < :k0 OR (t.updated_at = :k0 AND t.id > :k1))";
    }

    private static String order(boolean byRelevance) {
        return byRelevance
                ? "t.relevance DESC, t.updated_at DESC, t.id ASC"
                : "t.updated_at DESC, t.id ASC";
    }

    /**
     * Placeholders for the first page.
     *
     * <p>Bound because the parameters are named in the SQL either way; they are never read, since
     * {@code :hasCursor = FALSE} satisfies the predicate before them. Typed correctly all the same
     * — an integer parameter given an empty string would fail on the cast rather than be ignored.
     */
    private static List<String> blankKey(boolean byRelevance) {
        List<String> values = new ArrayList<>();
        if (byRelevance) {
            values.add("0");
        }
        values.add("");
        values.add("");
        return values;
    }
}
