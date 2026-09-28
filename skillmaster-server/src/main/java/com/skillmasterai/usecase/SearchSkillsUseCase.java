package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.search.SearchRequest;
import com.skillmasterai.modules.search.SkillSearchService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searches the caller's own skills.
 *
 * <p>Another thin one, and deliberately so: the interesting part of search is the query, which
 * belongs to M8, and the ownership rule, which belongs to M4. What the use-case layer adds is the
 * meeting — §3.4 requires the result to be confined to namespaces the caller may read, and M8
 * cannot work that out for itself.
 *
 * <p>Read-only, so the transaction exists for consistency rather than for atomicity: the page and
 * the cursor that follows from it have to come from the same snapshot, or a publish landing
 * mid-page could place a row on both sides of the boundary.
 */
@Component
public class SearchSkillsUseCase {

    private final NamespaceService namespaces;
    private final SkillSearchService search;

    public SearchSkillsUseCase(NamespaceService namespaces, SkillSearchService search) {
        this.namespaces = namespaces;
        this.search = search;
    }

    /**
     * @throws com.skillmasterai.modules.search.InvalidSearchRequestException for a limit below one
     *         or a cursor this version cannot read — both are the caller's to fix, and both are a
     *         400 rather than a silently repaired request
     */
    @Transactional(readOnly = true)
    public SkillSearchService.SearchPage search(SearchRequest request,
            AuthenticatedSubject subject) {
        // v1's answer, in one place: the caller searches their own namespace and no other. When
        // sharing arrives this is the line that changes — which is why it is a call rather than a
        // predicate M8 was handed.
        Namespace visible = namespaces.personalNamespaceOf(subject.userId());

        // §4.2: `namespace` narrows within what the caller may already see and is never a way to
        // widen. Resolving it here — rather than passing the slug into the query for a join —
        // keeps the filter a comparison of two values this layer already holds, and gives the
        // bypass its correct shape: a namespace that is not yours selects nothing, rather than
        // selecting someone else's skills.
        if (request.namespaceSlug() != null && !request.namespaceSlug().equals(visible.slug())) {
            return new SkillSearchService.SearchPage(List.of(), null);
        }
        return search.search(request, visible);
    }
}
