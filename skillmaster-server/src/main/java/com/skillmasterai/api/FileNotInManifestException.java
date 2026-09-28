package com.skillmasterai.api;

/**
 * §4.1's second 404: the address resolved, the manifest has no such file.
 *
 * <p>The counterpart of {@link SkillNotFoundException} rather than a special case of it. That one
 * answers "this address names nothing" and deliberately does not say which of its four reasons
 * applied; this one says something the caller can act on — the skill exists, the version exists,
 * and the file it asked for is not part of it. Folding the two together leaves §4.1's
 * {@code file_not_found} row describing a response the server never sends.
 */
class FileNotInManifestException extends RuntimeException {

    FileNotInManifestException(String relpath) {
        super("the version has no file at '" + relpath + "'");
    }
}
