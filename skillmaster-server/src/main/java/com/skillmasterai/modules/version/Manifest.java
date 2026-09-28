package com.skillmasterai.modules.version;

import com.skillmasterai.common.Sha256Hex;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A skill version's file set, in the one order that matters.
 *
 * <p><strong>Sorting happens here, in Java, exactly once.</strong> "Sorted by relpath" is
 * ambiguous — Java's {@code String.compareTo} (UTF-16 code units), PostgreSQL's default collation
 * and {@code COLLATE "C"} (UTF-8 bytes) are three different orders, and they disagree on ordinary
 * ASCII punctuation. The digest and the HTTP manifest must agree, or a client re-deriving the
 * digest computes something else and concludes the skill changed forever (ADR 0005 后果). So the
 * ordering is applied here and nowhere else, and no query is relied upon to produce it.
 *
 * <p>The digest is ADR 0005's, written down and not to be varied:
 * {@code sha256(concat over files sorted by relpath of (relpath + "\0" + blob_sha256 + "\0"))}.
 * It depends on nothing but content — not on filesystem traversal order, not on time — which is
 * what makes republishing identical content a no-op under {@code UNIQUE(skill_id, digest)}.
 */
public record Manifest(List<ManifestEntry> entries) {

    public Manifest {
        entries = entries.stream()
                .sorted(Comparator.comparing(ManifestEntry::relpath))
                .toList();
    }

    public static Manifest of(List<ManifestEntry> entries) {
        return new Manifest(entries);
    }

    /** Lowercase hex, as stored. The API prefixes it with {@code sha256:} when presenting it. */
    public String digest() {
        MessageDigest digest = Sha256Hex.newDigest();
        for (ManifestEntry entry : entries) {
            digest.update(entry.relpath().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.blobSha256().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public int fileCount() {
        return entries.size();
    }

    public long totalBytes() {
        return entries.stream().mapToLong(ManifestEntry::size).sum();
    }

    /** The entry for a path, or empty. Used by L3 to look a path up rather than resolve it. */
    public java.util.Optional<ManifestEntry> find(String relpath) {
        return entries.stream().filter(entry -> entry.relpath().equals(relpath)).findFirst();
    }
}
