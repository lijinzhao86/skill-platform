package com.skillmasterai.common;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * ULIDs: 128 bits rendered as 26 characters of Crockford base32 — a 48-bit millisecond
 * timestamp followed by 80 bits of randomness.
 *
 * <p>Chosen over an auto-increment or a bare UUID because it is opaque, sortable by
 * creation time, and does not leak row counts (ADR 0004). It is a legal identifier in a URL
 * without escaping, which matters because skill ids appear in paths.
 *
 * <p>The timestamp occupies 50 bits' worth of characters but only 48 bits of value, so the
 * first character never exceeds {@code 7}. {@link #isValid} enforces that.
 */
public final class Ulid {

    private Ulid() {
    }

    /** Crockford base32: no I, L, O or U, so the alphabet cannot be misread. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final int[] DECODED = new int[128];

    static {
        Arrays.fill(DECODED, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODED[ALPHABET[i]] = i;
        }
    }

    public static final int LENGTH = 26;

    private static final long MAX_TIMESTAMP = 0xFFFF_FFFF_FFFFL;
    private static final int TIMESTAMP_CHARS = 10;
    private static final int RANDOMNESS_BYTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate() {
        return generate(System.currentTimeMillis());
    }

    /** Deterministic apart from the random half; used by tests that need a known timestamp. */
    public static String generate(long epochMilli) {
        if (epochMilli < 0 || epochMilli > MAX_TIMESTAMP) {
            throw new IllegalArgumentException("timestamp out of ULID range: " + epochMilli);
        }

        char[] out = new char[LENGTH];

        long remaining = epochMilli;
        for (int i = TIMESTAMP_CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (remaining & 0x1F)];
            remaining >>>= 5;
        }

        byte[] randomness = new byte[RANDOMNESS_BYTES];
        RANDOM.nextBytes(randomness);

        // 80 random bits spread across the remaining 16 characters, most significant first.
        int buffer = 0;
        int bits = 0;
        int at = TIMESTAMP_CHARS;
        for (byte b : randomness) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out[at++] = ALPHABET[(buffer >>> bits) & 0x1F];
            }
        }
        return new String(out);
    }

    /**
     * Strictly validates what {@link #generate} produces: exactly 26 characters, uppercase
     * Crockford base32, and a first character within the 48-bit timestamp range. Lowercase
     * or the Crockford aliases (I, L, O) are rejected rather than silently folded — an id
     * that validates differently in two places is worse than one that fails loudly.
     */
    public static boolean isValid(CharSequence candidate) {
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = candidate.charAt(i);
            if (c >= DECODED.length || DECODED[c] < 0) {
                return false;
            }
        }
        return DECODED[candidate.charAt(0)] <= 7;
    }

    public static String requireValid(String candidate, String what) {
        if (!isValid(candidate)) {
            throw new IllegalArgumentException(what + " is not a valid ULID: " + candidate);
        }
        return candidate;
    }
}
