package com.skillmasterai.api;

import java.util.Locale;
import java.util.Map;

/**
 * The {@code Content-Type} for a stored file, decided by its extension (§4.2 L3).
 *
 * <p>An explicit table rather than {@code URLConnection.guessContentTypeFromName}, which reads a
 * properties file out of whichever JDK is running and answers differently on different platforms.
 * A client that gets a different type from the same server after a base-image change has no way to
 * explain it; a lookup table costs a few lines and is the same everywhere.
 *
 * <p>The table is deliberately short. Anything absent is {@code application/octet-stream}, which is
 * a correct answer — media types are a hint for the client, not a guarantee, and a wrong guess is
 * worse than an honest generic one.
 *
 * <p><strong>Text types carry {@code charset=UTF-8} and that is not decoration.</strong> HTTP
 * gives {@code text/*} a default charset of ISO-8859-1, so a skill whose SKILL.md is in Chinese
 * would arrive mojibake'd by a client that took the default at its word. The bytes are known to be
 * UTF-8 where they are text — {@code is_binary} in the manifest is exactly that determination.
 */
final class MediaTypes {

    private static final String OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("md", "text/markdown;charset=UTF-8"),
            Map.entry("markdown", "text/markdown;charset=UTF-8"),
            Map.entry("txt", "text/plain;charset=UTF-8"),
            Map.entry("csv", "text/csv;charset=UTF-8"),
            Map.entry("json", "application/json;charset=UTF-8"),
            Map.entry("yaml", "application/yaml;charset=UTF-8"),
            Map.entry("yml", "application/yaml;charset=UTF-8"),
            Map.entry("xml", "application/xml;charset=UTF-8"),
            Map.entry("html", "text/html;charset=UTF-8"),
            Map.entry("css", "text/css;charset=UTF-8"),
            Map.entry("js", "text/javascript;charset=UTF-8"),
            Map.entry("py", "text/x-python;charset=UTF-8"),
            Map.entry("sh", "text/x-shellscript;charset=UTF-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"));

    private MediaTypes() {
    }

    static String forRelpath(String relpath) {
        int dot = relpath.lastIndexOf('.');
        // A dot in a directory name is not an extension: "v1.2/notes" has none.
        int slash = relpath.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == relpath.length() - 1) {
            return OCTET_STREAM;
        }
        return BY_EXTENSION.getOrDefault(relpath.substring(dot + 1).toLowerCase(Locale.ROOT),
                OCTET_STREAM);
    }

    /** §4.2 fixes this one: the body endpoint always serves markdown. */
    static final String MARKDOWN = "text/markdown;charset=UTF-8";
}
