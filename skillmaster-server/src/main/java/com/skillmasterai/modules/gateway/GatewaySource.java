package com.skillmasterai.modules.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the gateway skill's source off the classpath and points it at this deployment.
 *
 * <p>The source is authoritative at {@code gateway/skillmaster/SKILL.md} in the repository root,
 * shared with the CLI. The build copies that directory onto the classpath (see {@code pom.xml})
 * rather than committing a second copy, so there is exactly one file anyone can edit.
 *
 * <p><strong>The substitution happens here, once, at publish time.</strong> The source contains a
 * literal {@code https://<host>} placeholder because the repository cannot know a deployment's
 * address; the published skill contains the real one. Doing it per request instead would mean the
 * bytes served differed from the bytes the version digest was taken over, and a client that
 * verified the digest would find the skill permanently changed — §4.2's "hosting must be faithful"
 * is the rule that forbids it.
 *
 * <p>It is also deterministic: no timestamp, no random, nothing that varies between two runs on the
 * same configuration. That is what makes re-publishing on every startup a no-op rather than a new
 * version each time (§5.2: the gateway body <em>is</em> the protocol, so a restart must not make
 * every installed client believe it is stale).
 */
public final class GatewaySource {

    /**
     * The placeholder the repository's copy carries.
     *
     * <p>§5.1 leaves the address out of the source on purpose: the file is committed to a public
     * repository and installed on machines that have not yet talked to any server, so it cannot
     * name one. This is the string the deployment replaces.
     */
    public static final String HOST_PLACEHOLDER = "https://<host>";

    private static final String RESOURCE_DIRECTORY = "gateway/";
    private static final String SKILL_FILE = "SKILL.md";

    private GatewaySource() {
    }

    /**
     * The gateway skill's files, with the placeholder already replaced.
     *
     * <p>Keyed by path relative to the skill's own directory, because that is what the archive and
     * the manifest both need. Today it holds one file; the directory is walked anyway so that
     * adding a reference file to the gateway does not require changing this method.
     *
     * @param publicBaseUrl the address clients reach this deployment at
     */
    public static Map<String, byte[]> read(String publicBaseUrl) {
        // The directory is named after the skill, which §1.3 makes a MUST — so it is built from the
        // same constant the published name is checked against, and the two cannot drift apart here.
        String raw = readResource(
                RESOURCE_DIRECTORY + GatewayService.SKILL_NAME + "/" + SKILL_FILE);
        if (!raw.contains(HOST_PLACEHOLDER)) {
            // Loud, because the alternative is a published gateway telling every client to call
            // the literal string "https://<host>". If the placeholder is ever reworded in the
            // source, the build should say so rather than ship a skill that cannot work.
            throw new IllegalStateException("the gateway source has no '" + HOST_PLACEHOLDER
                    + "' placeholder, so its address cannot be set to " + publicBaseUrl);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(SKILL_FILE,
                raw.replace(HOST_PLACEHOLDER, publicBaseUrl).getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static String readResource(String path) {
        ClassLoader loader = GatewaySource.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("the gateway source '" + path
                        + "' is not on the classpath; the build copies it from the repository root");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
