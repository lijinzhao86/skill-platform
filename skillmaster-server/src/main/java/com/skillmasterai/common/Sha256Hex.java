package com.skillmasterai.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 as lowercase hex, the one form used everywhere: blob addresses, version digests,
 * and the well-known index digest.
 *
 * <p>Lowercase hex specifically, because a digest is compared as a string in several places
 * (the {@code blob} primary key, {@code version_file.blob_sha256}, the published index). Two
 * spellings of the same digest would be two different blobs.
 */
public final class Sha256Hex {

    private Sha256Hex() {
    }

    private static final HexFormat HEX = HexFormat.of();

    /** For callers that hash incrementally, e.g. the digest over a file set. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every conforming JRE is required to ship SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }

    public static String of(byte[] data) {
        return HEX.formatHex(newDigest().digest(data));
    }
}
