package com.skillmasterai.modules.gateway.internal;

import com.skillmasterai.common.Sha256Hex;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The digest the well-known index declares, which is <strong>not</strong> a version digest.
 *
 * <p>There are two, they hash different things, and mixing them up is silent. ADR 0005's version
 * digest hashes {@code relpath + NUL + blob_sha256 + NUL} — the manifest, so it can be computed
 * without reading content. This one hashes {@code path + NUL + bytes + NUL} over the file
 * <em>contents</em>, because it is not ours: it is what the {@code skills} CLI computes for itself
 * when it decides whether a hosted skill has changed (§1.5, reverse-engineered from
 * {@code computeWellKnownSkillDigest} in the cached 1.5.24 build).
 *
 * <p>Declaring a version digest in a V2 index would make the client compare its own content hash
 * against a manifest hash, find them unequal every time, and conclude the skill had changed on
 * every single check — the failure mode ADR 0005 warns about, arriving as a client that re-downloads
 * forever rather than as an error.
 *
 * <p>The separator is NUL, not a space. That is not a detail to be tidied: a space would let
 * {@code ("a b", "c")} and {@code ("a", "b c")} collide, and the measured behaviour is NUL.
 *
 * <p><strong>Unverified against a real client.</strong> This reproduces the algorithm as read from
 * a decompiled CLI; whether the server's expectation is exactly this cannot be confirmed until
 * P0b's Go CLI fetches from a live server. The frozen vectors below pin the implementation against
 * itself, which is all a test can do until then.
 */
public final class WellKnownDigest {

    private WellKnownDigest() {
    }

    /** A file as the digest sees it: a path and its content. */
    public record File(String path, byte[] bytes) {
    }

    /**
     * @param files hashed in path order, which is applied here rather than trusted from the caller
     *              — the CLI sorts by path too, and a different traversal order would produce a
     *              different digest for identical content
     */
    public static String of(List<File> files) {
        MessageDigest digest = Sha256Hex.newDigest();
        files.stream()
                .sorted(Comparator.comparing(File::path))
                .forEach(file -> {
                    digest.update(file.path().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(file.bytes());
                    digest.update((byte) 0);
                });
        return HexFormat.of().formatHex(digest.digest());
    }
}
