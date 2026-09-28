package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDetail;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading a skill at L1 (detail), L2 (body) and L3 (one file) — one use case, three answers.
 *
 * <p>They are one use case because they differ only in how much they return; splitting them would
 * duplicate the step that actually matters. That step is the composition §2.5 names "读详情": M3
 * has already established who is asking, M4 resolves which namespace they own, and M9 does the
 * reading with that namespace as a predicate. Answering "which skill may I see" in any one of
 * those modules alone would be wrong — M4 cannot name the {@code skill} table, and M9 must not
 * decide policy — so the meeting point is here.
 *
 * <p>{@link NamespaceService#readableNamespaceOf} therefore runs on every call, including the ones
 * that turn out to be 404. That is the point: it is not an optimization to be skipped when the
 * answer "looks like" it will be empty.
 *
 * <p>Read-only, so there is no transaction to draw — {@code @Transactional(readOnly = true)} is
 * declared anyway so that the three-query read sees one consistent snapshot rather than a version
 * that could be swapped underneath it by a concurrent publish.
 */
@Component
public class ReadSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillDistributionService distribution;

    public ReadSkillUseCase(NamespaceService namespaces, SkillDistributionService distribution) {
        this.namespaces = namespaces;
        this.distribution = distribution;
    }

    /** L1: the full manifest and no content. */
    @Transactional(readOnly = true)
    public Optional<SkillDetail> detail(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        return namespaces.readableNamespaceOf(subject.userId(), namespaceSlug)
                .flatMap(namespace -> distribution.detailOf(namespace, name, pin));
    }

    /** L2: the original {@code SKILL.md} bytes. */
    @Transactional(readOnly = true)
    public Optional<byte[]> body(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        return namespaces.readableNamespaceOf(subject.userId(), namespaceSlug)
                .flatMap(namespace -> distribution.bodyOf(namespace, name, pin));
    }

    /**
     * L3: one file's original bytes, by exact {@code relpath}.
     *
     * <p>The result keeps §4.1's two 404s apart — nothing at this address, versus a version that
     * resolved and does not list the file. See {@link SkillDistributionService.FileLookup}.
     */
    @Transactional(readOnly = true)
    public Optional<SkillDistributionService.FileLookup> file(String namespaceSlug, String name,
            VersionPin pin, String relpath, AuthenticatedSubject subject) {
        return namespaces.readableNamespaceOf(subject.userId(), namespaceSlug)
                .flatMap(namespace -> distribution.fileOf(namespace, name, pin, relpath));
    }
}
