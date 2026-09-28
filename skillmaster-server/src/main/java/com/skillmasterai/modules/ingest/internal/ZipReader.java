package com.skillmasterai.modules.ingest.internal;

import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.ingest.IngestedFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Turns a zip into a flat list of files, or refuses it.
 *
 * <p>Everything hostile about zip lives here, because a zip is a file format that can describe
 * paths the receiving filesystem would do something surprising with, and we are not extracting to
 * a filesystem but the same assumptions leak into whatever consumes the names later.
 *
 * <p>Rejections, each with its own reason so the author can act:
 * <ul>
 *   <li><strong>Symlink entries.</strong> A zip stores a symlink as a regular entry whose unix
 *       mode says so. Following it would mean publishing bytes the author never uploaded, and the
 *       digest would then describe a file the author cannot see. {@code java.util.zip} cannot
 *       detect this at all, which is why commons-compress is a dependency.</li>
 *   <li><strong>Absolute paths and {@code ..} segments.</strong> Classic archive traversal. We do
 *       not extract to disk, but {@code relpath} is stored and later used as a lookup key, so a
 *       name that means "somewhere else" must never enter the system.</li>
 *   <li><strong>Backslashes.</strong> A Windows-made zip uses them, and a name containing one
 *       would be a single opaque segment on this side rather than a path — silently a different
 *       file than the author intended. <em>This one cannot currently fire</em>: commons-compress
 *       rewrites {@code \} to {@code /} while reading, as well as while writing, so no backslash
 *       ever reaches this code. It is kept anyway — the property is ours, not the reader's, and it
 *       costs three lines if the reader is ever swapped. {@code ZipsTest} pins the behaviour rather
 *       than the check, since the check has no reachable input.</li>
 *   <li><strong>Two entries with the same path.</strong> The zip format permits it and {@code
 *       unzip} merely warns, but a skill's manifest is keyed by {@code relpath}: the second entry
 *       would collide with the first on {@code PRIMARY KEY (version_id, relpath)} and surface as a
 *       500 from the insert rather than as a rejected upload.</li>
 * </ul>
 *
 * <p>Decompression is bounded while reading, not merely checked against the declared size first:
 * a zip bomb declares a small size and delivers a large one, so the declared total is only a cheap
 * early exit.
 */
public final class ZipReader {

    private ZipReader() {
    }

    private static final String SEPARATOR = "/";
    private static final String PARENT = "..";
    private static final int BUFFER = 8192;

    public static List<IngestedFile> read(byte[] zipBytes, IngestLimits limits) {
        try (ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zipBytes))
                .get()) {

            List<IngestedFile> files = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            long declaredTotal = 0;

            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String relpath = validateName(entry.getName());
                if (!seen.add(relpath)) {
                    throw new IngestException("the upload contains two entries with the same path",
                            relpath, "duplicate_relpath");
                }

                if (entry.isUnixSymlink()) {
                    throw new IngestException("the upload contains a symbolic link",
                            relpath, "symlink_not_allowed");
                }

                declaredTotal += Math.max(entry.getSize(), 0);
                if (files.size() + 1 > limits.maxFiles()) {
                    throw tooManyFiles(limits);
                }
                if (declaredTotal > limits.maxTotalBytes()) {
                    throw tooLarge(limits);
                }

                byte[] bytes = readBounded(zip, entry, limits, files);
                files.add(new IngestedFile(relpath, bytes));
            }

            if (files.isEmpty()) {
                throw new IngestException("the upload contains no files", "file", "empty_archive");
            }
            return files;
        } catch (IOException e) {
            throw new IngestException("the upload is not a readable zip archive",
                    "file", "not_a_zip");
        }
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IngestException("the upload contains an entry with no name",
                    "files[]", "empty_name");
        }
        if (name.startsWith(SEPARATOR)) {
            throw new IngestException("the upload contains an absolute path",
                    name, "absolute_path");
        }
        if (name.contains("\\")) {
            throw new IngestException("the upload contains a Windows-style path separator",
                    name, "backslash_in_path");
        }
        for (String segment : name.split(SEPARATOR, -1)) {
            if (segment.equals(PARENT)) {
                throw new IngestException("the upload contains a path that escapes the skill root",
                        name, "path_traversal");
            }
            if (segment.isEmpty()) {
                throw new IngestException("the upload contains an empty path segment",
                        name, "empty_path_segment");
            }
        }
        return name;
    }

    private static byte[] readBounded(ZipFile zip, ZipArchiveEntry entry, IngestLimits limits,
            List<IngestedFile> alreadyRead) throws IOException {
        long already = alreadyRead.stream().mapToLong(IngestedFile::size).sum();
        long remaining = limits.maxTotalBytes() - already;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[BUFFER];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > remaining) {
                    throw tooLarge(limits);
                }
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static IngestException tooManyFiles(IngestLimits limits) {
        return new IngestException(
                "the upload has more than " + limits.maxFiles() + " files",
                "files", "too_many_files");
    }

    private static IngestException tooLarge(IngestLimits limits) {
        return new IngestException(
                "the upload exceeds " + limits.maxTotalBytes() + " bytes uncompressed",
                "files", "too_large");
    }
}
