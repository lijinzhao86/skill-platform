package com.skillmasterai.modules.audit;

import java.util.Map;

/**
 * One thing that happened, worth being able to answer questions about later.
 *
 * <p>§3.5 makes this the floor rather than a nicety: skill content is taken away and used in
 * environments we do not control, so after-the-fact attribution is the only trace there is.
 * Publishing, deleting, rolling back, and issuing or revoking a token all record one.
 *
 * @param actorUserId who did it, or null when there is no user behind it — a background sweep, or
 *                    a token operation performed by the system
 * @param action      what happened, e.g. {@code publish}, {@code delete}
 * @param targetType  what it happened to, e.g. {@code skill}
 * @param targetId    which one
 * @param detail      anything else worth keeping; stored as JSON and never queried by field
 */
public record AuditEvent(
        String actorUserId,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> detail) {

    public AuditEvent {
        detail = Map.copyOf(detail);
    }

    public static AuditEvent of(String actorUserId, String action, String targetType, String targetId) {
        return new AuditEvent(actorUserId, action, targetType, targetId, Map.of());
    }
}
