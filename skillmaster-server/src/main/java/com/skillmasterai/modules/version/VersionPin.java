package com.skillmasterai.modules.version;

/**
 * Which of a skill's versions an address asked for (§4.1).
 *
 * <p>Three forms, and only the first is not a pin: an address with no {@code @} suffix means
 * {@code latest}, which is resolved afresh on every request, so a task that keeps entering the same
 * address drifts as soon as anyone publishes. The other two are pins — data the client carries, not
 * state the server keeps (ADR 0012) — and that is what makes a manifest and the bytes fetched from
 * it stay in agreement.
 *
 * <p>Sealed so resolution must handle every form: a new pin kind added later becomes a compile error
 * at each place that resolves one, rather than a lookup that silently falls through to the default.
 *
 * <p>Lives in M7 rather than the use-case layer because the versions it selects are rows of a table
 * M7 owns, and because §2.5 forbids a module depending on a use case. The wire spelling that
 * produces one is the API's business — see {@code SkillAddress}.
 */
public sealed interface VersionPin {

    /** No suffix: the skill's current version, re-resolved on every request. */
    record Latest() implements VersionPin {
    }

    /**
     * {@code @3} — the skill's Nth distinct content.
     *
     * <p>An immutable alias rather than a sequence number: {@code UNIQUE (skill_id, number)} makes it
     * a stable name for one piece of content, so {@code @3} means the same bytes forever (ADR 0012).
     */
    record Number(int number) implements VersionPin {
    }

    /**
     * {@code @sha256:…} — pinned to content, the identity rather than the alias.
     *
     * <p>Bare lowercase hex, which is how storage writes a digest (ADR 0005); the {@code sha256:}
     * prefix an address carries is a presentation concern the API strips.
     */
    record Digest(String sha256Hex) implements VersionPin {
    }
}
