package com.skillmasterai.common;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Timestamps are RFC3339 UTC strings (technical-design.md ch.3).
 *
 * <p>Truncating to whole seconds is not cosmetic. Without it the strings carry a variable
 * number of fractional digits, and lexicographic order stops matching chronological order —
 * which both the keyset cursors and the {@code audit_event(at DESC)} index depend on.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static String now() {
        return format(Instant.now());
    }

    public static String format(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    public static Instant parse(String rfc3339) {
        return Instant.parse(rfc3339);
    }
}
