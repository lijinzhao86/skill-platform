package com.skillmasterai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link Zips} actually produces.
 *
 * <p>Not ceremony. Two validator tests were passing or failing for fixture reasons rather than
 * validator reasons — first because commons-compress silently sanitises names, then because both
 * writers rewrite backslashes. Each of those cost a confusing round of debugging, and each is a
 * property of the tooling that is invisible from the test that depends on it.
 */
class ZipsTest {

    @Test
    void ofSanitisesNothingButRewritesBackslashes() throws IOException {
        // commons-compress keeps a leading slash and a parent segment, and rewrites '\' to '/'.
        byte[] zip = Zips.ofText(Map.of(
                "SKILL.md", "x",
                "/etc/passwd", "y",
                "../evil.md", "z",
                "references\\win.md", "w"));

        assertThat(entryNames(zip))
                .containsExactlyInAnyOrder("SKILL.md", "/etc/passwd", "../evil.md", "references/win.md");
    }

    @Test
    void ofRawPathsKeepsNamesThatCommonsCompressWouldRewrite() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", bytes("x"));
        entries.put("/etc/passwd", bytes("y"));
        entries.put("../evil.md", bytes("z"));

        assertThat(entryNames(Zips.ofRawPaths(entries)))
                .containsExactlyInAnyOrder("SKILL.md", "/etc/passwd", "../evil.md");
    }

    @Test
    void aBackslashInTheBytesIsNormalisedOnTheWayBackOut() throws IOException {
        // The backslash is really in the archive — patchedNames rewrote the bytes — and it still
        // reads back as a forward slash. commons-compress normalises on read as well as on write,
        // which is why ZipReader's backslash rejection has no reachable input and why this pins
        // the behaviour instead of the check.
        byte[] zip = Zips.ofRawPaths(new LinkedHashMap<>(Map.of(
                "SKILL.md", bytes("x"),
                "references/win.md", bytes("y"))));

        byte[] patched = Zips.patchedNames(zip, "references/win.md", "references\\win.md");

        assertThat(patched).as("the patched bytes really contain a backslash")
                .containsSubsequence("references\\win.md".getBytes(StandardCharsets.UTF_8));
        assertThat(entryNames(patched))
                .as("but the reader hands them back with a forward slash")
                .containsExactlyInAnyOrder("SKILL.md", "references/win.md");
    }

    @Test
    void patchedNamesRefusesALengthChange() {
        byte[] zip = Zips.ofText(Map.of("SKILL.md", "x"));

        assertThatThrownBy(() -> Zips.patchedNames(zip, "SKILL.md", "a/longer/name.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
        assertThatThrownBy(() -> Zips.patchedNames(zip, "absent.md", "absent.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no entry named");
    }

    @Test
    void aSymlinkEntryKeepsItsUnixMode() throws IOException {
        byte[] zip = Zips.withSymlink("SKILL.md", "/etc/passwd");

        try (ZipFile file = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip))
                .get()) {
            ZipArchiveEntry entry = file.getEntry("SKILL.md");
            assertThat(entry).isNotNull();
            assertThat(entry.isUnixSymlink()).isTrue();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> entryNames(byte[] zip) throws IOException {
        Map<String, String> names = new LinkedHashMap<>();
        try (ZipFile file = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip))
                .get()) {
            for (ZipArchiveEntry entry : Collections.list(file.getEntries())) {
                names.put(entry.getName(),
                        new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return List.copyOf(names.keySet());
    }
}
