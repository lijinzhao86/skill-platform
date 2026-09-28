package com.skillmasterai.modules.version;

/**
 * One file of a skill version: where it sits, and which blob holds its bytes.
 *
 * @param relpath     path relative to the skill root, POSIX separators
 * @param blobSha256  lowercase hex digest of the bytes, the address in {@code blob}
 * @param size        byte count
 * @param isBinary    whether the bytes are not valid UTF-8; a hint for clients, not a rule
 */
public record ManifestEntry(String relpath, String blobSha256, long size, boolean isBinary) {
}
