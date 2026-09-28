package com.skillmasterai.modules.namespace;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.namespace.internal.NamespaceRepository;

/**
 * M4's public face.
 *
 * <p>What is <em>not</em> here is as deliberate as what is: there is no "may this caller read this
 * skill" method, because that decision has to be made where the skill is known and its answer has
 * to be rendered as 404 rather than 403 (§4.2). A predicate exposed here would invite the
 * authorization layer to turn it into a 403, which is the mistake that rule exists to prevent.
 * v1's answer is simply "the caller's own namespace", applied by each use case.
 */
public final class NamespaceService {

    private final NamespaceRepository repository;
    private final AccountDirectory accounts;

    public NamespaceService(NamespaceRepository repository, AccountDirectory accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    /**
     * The user's personal namespace: the one they own whose slug is their handle (§3.2).
     *
     * <p>Two calls rather than one join, because the handle is M1's column and this is M4 — see
     * {@link NamespaceRepository#findByOwnerAndSlug}. Both are primary-key lookups.
     *
     * @throws IllegalStateException if the user does not exist, or exists without a personal
     *         namespace. Neither is a client error: registration creates the user and their
     *         namespace atomically, so either means the database is in a state the application
     *         never writes. Quietly returning empty would turn that into a 404 for a caller who
     *         did nothing wrong.
     */
    public Namespace personalNamespaceOf(String userId) {
        String handle = accounts.handleOf(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "no user " + userId + "; a token's subject must name one"));
        return repository.findByOwnerAndSlug(userId, handle)
                .orElseThrow(() -> new IllegalStateException(
                        "user " + userId + " has no personal namespace; registration creates one"));
    }

    /**
     * A namespace by its slug, whoever owns it.
     *
     * <p>For the reserved namespace the gateway skill lives in. Note what this is <em>not</em>: a
     * way to read someone else's skills. It resolves a namespace, and every caller still has to
     * state which namespace it is willing to read from — see {@code SkillVersionService}.
     *
     * @throws IllegalStateException if there is no such namespace. Slugs are seeded or created at
     *         registration, so a missing one means the deployment is not the one the caller assumes
     */
    public Namespace namespaceOfSlug(String slug) {
        return repository.findBySlug(slug)
                .orElseThrow(() -> new IllegalStateException("no namespace with slug '" + slug + "'"));
    }
}
