package com.skillmasterai.api;

/**
 * The address names nothing the caller may read.
 *
 * <p><strong>Four different failures deliberately collapse into this one exception</strong> (§4.1):
 * there is no such skill, it belongs to a namespace the caller does not own, it was soft-deleted, or
 * the version the address pins does not exist. Also a name whose {@code @version} suffix is not a
 * version at all — that is the same kind of nothing. One code and one status for all of them, because
 * every extra distinction is a way to probe for what exists: a 403 would confirm a private skill is
 * there, and even a distinct error code would leak the same fact more quietly.
 *
 * <p>Thrown from the API layer rather than returned as an empty {@code Optional}, because the body
 * has to be §4.1's envelope and two of the endpoints return raw bytes, which cannot carry one.
 * {@link ApiExceptionHandler} is where that envelope is built.
 */
class SkillNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SkillNotFoundException() {
        // One message for every case, for the reason above: a message that said which one it was
        // would undo the point of sharing a code.
        super("no skill at that address");
    }
}
