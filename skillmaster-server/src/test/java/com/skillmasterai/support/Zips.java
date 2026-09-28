package com.skillmasterai.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * Builds zips for tests.
 *
 * <p>Uses commons-compress rather than {@code java.util.zip} because entry unix modes have to be
 * settable — a symlink entry is only a symlink because its mode says so, and {@code java.util.zip}
 * cannot express that, which is precisely why the validator needs commons-compress to detect one.
 */
public final class Zips {

    private Zips() {
    }

    private static final int REGULAR_FILE = 0100644;
    private static final int DIRECTORY = 040755;
    private static final int SYMLINK = 0120777;

    /**
     * A zip of regular files, keyed by path. Insertion order is preserved.
     *
     * <p>Both this and {@link #ofRawPaths} rewrite {@code \} as {@code /} while writing — the ZIP
     * spec mandates {@code /} as the separator, so neither tool will produce a backslash entry.
     * To test the reader's rejection of one, build with {@code /} and then rewrite the bytes with
     * {@link #patchedNames}.
     */
    public static byte[] of(Map<String, byte[]> entries) {
        return build(out -> entries.forEach((path, bytes) -> write(out, path, bytes, REGULAR_FILE)));
    }

    /**
     * Rewrites occurrences of one entry name to another of the same length, byte for byte.
     *
     * <p>Equal length is required and checked: the name appears in both the local header and the
     * central directory, and changing its length would invalidate every offset in the archive.
     * Same-length rewriting is what makes a shape no archive writer will produce reachable in a
     * test — currently only the backslash.
     */
    public static byte[] patchedNames(byte[] zip, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("names must be the same length: " + from + " / " + to);
        }
        byte[] needle = from.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = to.getBytes(StandardCharsets.UTF_8);
        byte[] patched = zip.clone();

        int replacements = 0;
        for (int i = 0; i + needle.length <= patched.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length && matches; j++) {
                matches = patched[i + j] == needle[j];
            }
            if (matches) {
                System.arraycopy(replacement, 0, patched, i, replacement.length);
                replacements++;
                i += needle.length - 1;
            }
        }
        if (replacements == 0) {
            throw new IllegalArgumentException("no entry named " + from + " in this archive");
        }
        return patched;
    }

    /**
     * A zip whose entry names are written as given, using {@code java.util.zip}.
     *
     * <p>Needed for the names commons-compress will not write at all: {@code ../evil.md} and
     * {@code /etc/passwd} both survive here and neither survives {@link #of}. Backslashes do not
     * survive either way — see {@link #patchedNames}.
     *
     * <p>No unix modes here — {@code java.util.zip} cannot express them — so this cannot build a
     * symlink entry. That is what {@link #withSymlink} is for, and the two exist side by side
     * because each tool can express one of the two things the reader has to detect.
     */
    public static byte[] ofRawPaths(java.util.Map<String, byte[]> entries) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static byte[] ofText(Map<String, String> entries) {
        Map<String, byte[]> asBytes = new LinkedHashMap<>();
        entries.forEach((path, text) -> asBytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return of(asBytes);
    }

    /** A zip holding one symlink entry alongside nothing else. */
    public static byte[] withSymlink(String linkPath, String target) {
        return build(out -> write(out, linkPath, target.getBytes(StandardCharsets.UTF_8), SYMLINK));
    }

    /** A zip holding one directory entry, which readers must ignore. */
    public static byte[] withDirectoryEntry(String path, Map<String, byte[]> files) {
        return build(out -> {
            write(out, path.endsWith("/") ? path : path + "/", new byte[0], DIRECTORY);
            files.forEach((file, bytes) -> write(out, file, bytes, REGULAR_FILE));
        });
    }

    private interface ZipBody {
        void write(ZipArchiveOutputStream out) throws IOException;
    }

    private static byte[] build(ZipBody body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            body.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ZipArchiveOutputStream out, String path, byte[] content, int mode) {
        try {
            ZipArchiveEntry entry = new ZipArchiveEntry(path);
            entry.setUnixMode(mode);
            out.putArchiveEntry(entry);
            out.write(content);
            out.closeArchiveEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
