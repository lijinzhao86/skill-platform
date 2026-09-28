package com.skillmasterai.common;

/**
 * The stable, machine-readable half of an error. Clients switch on these strings, so the wire
 * names are the contract and the enum constant names are not — renaming a constant must not
 * rename the wire value.
 *
 * <p>Note that {@link #SKILL_NOT_FOUND} covers both "no such skill" and "not yours". That is
 * deliberate and load-bearing: §4.2 requires a 404 rather than a 403 for a private skill the
 * caller may not see, because a 403 would confirm the skill exists. The message may differ; the
 * code and status may not.
 */
public enum ErrorCode {

    UNAUTHENTICATED("unauthenticated"),
    INSUFFICIENT_SCOPE("insufficient_scope"),
    INVALID_REQUEST("invalid_request"),
    INVALID_UPLOAD("invalid_upload"),
    SKILL_NOT_FOUND("skill_not_found"),
    FILE_NOT_FOUND("file_not_found"),
    INTERNAL_ERROR("internal_error");

    private final String wireName;

    ErrorCode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
