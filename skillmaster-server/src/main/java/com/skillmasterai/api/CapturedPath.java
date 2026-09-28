package com.skillmasterai.api;

/**
 * Turns Spring's rest capture into a path relative to a skill root.
 *
 * <p>{@code {*relpath}} captures <strong>with</strong> its leading separator: a request for
 * {@code /files/references/x.md} binds {@code relpath} to {@code "/references/x.md"}. A stored
 * {@code relpath} never has one — M5 rejects absolute paths — so without this the lookup misses
 * every file and the answer is 404. That was measured, not assumed; L3 404'd for every file until a
 * test printed the captured value.
 *
 * <p><strong>This is not normalisation and must not become any.</strong> It removes a routing
 * artifact so the value is the path the manifest is keyed by; nothing else is altered. No
 * collapsing, no resolving of {@code .} or {@code ..}, no percent-decoding beyond what the
 * container already did — a name that means something else has to miss, not be reinterpreted into a
 * hit.
 */
final class CapturedPath {

    private CapturedPath() {
    }

    static String relativeToSkillRoot(String captured) {
        return captured.startsWith("/") ? captured.substring(1) : captured;
    }
}
