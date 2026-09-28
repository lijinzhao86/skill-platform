package com.skillmasterai.modules.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManifestTest {

    private static final ManifestEntry SKILL_MD =
            new ManifestEntry("SKILL.md", "aa11", 10, false);
    private static final ManifestEntry REFERENCE =
            new ManifestEntry("references/checklist.md", "bb22", 20, false);

    @Test
    void entriesAreOrderedByRelpathWhateverOrderTheyArriveIn() {
        // The digest and the HTTP manifest must agree on one order. Three plausible ones exist
        // (Java String order, PostgreSQL's default collation, COLLATE "C") and they disagree on
        // ordinary punctuation, so the order is fixed here and never taken from a query.
        List<ManifestEntry> shuffled = new ArrayList<>(List.of(REFERENCE, SKILL_MD));

        assertThat(Manifest.of(shuffled).entries())
                .extracting(ManifestEntry::relpath)
                .containsExactly("SKILL.md", "references/checklist.md");
    }

    @Test
    void digestDoesNotDependOnInputOrder() {
        assertThat(Manifest.of(List.of(REFERENCE, SKILL_MD)).digest())
                .isEqualTo(Manifest.of(List.of(SKILL_MD, REFERENCE)).digest());
    }

    @Test
    void digestFollowsTheAlgorithmAdr0005Fixes() {
        // Computed here from the definition rather than by calling the production code, so that
        // an accidental change to the algorithm in Manifest shows up as a failure instead of
        // being confirmed by itself.
        MessageDigest expected = newDigest();
        expected.update("SKILL.md".getBytes(StandardCharsets.UTF_8));
        expected.update((byte) 0);
        expected.update("aa11".getBytes(StandardCharsets.UTF_8));
        expected.update((byte) 0);
        expected.update("references/checklist.md".getBytes(StandardCharsets.UTF_8));
        expected.update((byte) 0);
        expected.update("bb22".getBytes(StandardCharsets.UTF_8));
        expected.update((byte) 0);

        assertThat(Manifest.of(List.of(SKILL_MD, REFERENCE)).digest())
                .isEqualTo(java.util.HexFormat.of().formatHex(expected.digest()));
    }

    @Test
    void anyChangeToTheFileSetChangesTheDigest() {
        String base = Manifest.of(List.of(SKILL_MD)).digest();

        assertThat(Manifest.of(List.of(new ManifestEntry("SKILL.md", "cc33", 10, false))).digest())
                .as("different content")
                .isNotEqualTo(base);
        assertThat(Manifest.of(List.of(new ManifestEntry("SKILL-m.md", "aa11", 10, false))).digest())
                .as("different path for the same content")
                .isNotEqualTo(base);
        assertThat(Manifest.of(List.of(SKILL_MD, REFERENCE)).digest())
                .as("an added file")
                .isNotEqualTo(base);
        assertThat(Manifest.of(List.of(new ManifestEntry("SKILL.md", "aa11", 999, false))).digest())
                .as("size is not part of the digest, so this must be equal")
                .isEqualTo(base);
    }

    @Test
    void reportsCountsAndLooksUpByExactPath() {
        Manifest manifest = Manifest.of(List.of(REFERENCE, SKILL_MD));

        assertThat(manifest.fileCount()).isEqualTo(2);
        assertThat(manifest.totalBytes()).isEqualTo(30);
        assertThat(manifest.find("SKILL.md")).contains(SKILL_MD);
        assertThat(manifest.find("./SKILL.md"))
                .as("lookup is exact — no normalisation, because L3 must not resolve paths")
                .isEmpty();
        assertThat(manifest.find("references/")).isEmpty();
    }

    @Test
    void anEmptyManifestStillHasADigest() {
        assertThat(Manifest.of(List.of()).digest()).isEqualTo(sha256OfNothing());
    }

    private static String sha256OfNothing() {
        return java.util.HexFormat.of().formatHex(newDigest().digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
