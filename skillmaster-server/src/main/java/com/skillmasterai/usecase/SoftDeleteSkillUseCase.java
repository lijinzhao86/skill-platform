package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillVersionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soft-deletes one skill.
 *
 * <p>Deleting something that is not yours and deleting something that does not exist produce the
 * same answer — {@code false}, which the controller renders as 404 — and that is deliberate. §4.2
 * requires an unauthorized read of a private skill to be a 404 rather than a 403 because a 403
 * confirms the skill exists; the same reasoning applies to a delete, where the leak would be just
 * as good.
 *
 * <p>That equivalence is enforced in one statement rather than by comparing two lookups: the
 * namespace predicate is part of the {@code UPDATE}, so there is no window between deciding and
 * acting, and no second branch that could be edited into a 403.
 */
@Component
public class SoftDeleteSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final AuditLog audit;

    public SoftDeleteSkillUseCase(NamespaceService namespaces, SkillVersionService versions,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.audit = audit;
    }

    /** @return whether a skill the caller owns was deleted */
    @Transactional
    public boolean softDelete(String skillId, AuthenticatedSubject subject) {
        String ownNamespaceId = namespaces.personalNamespaceOf(subject.userId()).id();

        boolean deleted = versions.softDelete(skillId, ownNamespaceId);
        if (deleted) {
            // Inside the transaction on purpose: a trace that can outlive the rollback of the
            // change it describes reads as evidence of something that never happened.
            audit.record(AuditEvent.of(subject.userId(), "delete", "skill", skillId));
        }
        return deleted;
    }
}
