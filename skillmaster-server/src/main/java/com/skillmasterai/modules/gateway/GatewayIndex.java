package com.skillmasterai.modules.gateway;

import java.util.List;

/**
 * The two index documents §4.5 requires, as values rather than as JSON.
 *
 * <p>Both describe the same single skill. They exist side by side because the client's preferred
 * path ({@code agent-skills/}) only appeared in version 1.4.6 of the {@code skills} CLI — anything
 * older reads the legacy {@code skills/} path and nothing else (§1.5). Publishing one of them would
 * silently exclude whichever clients are on the other side of that line, and there is no way to
 * tell from a request which one is asking.
 *
 * <p>Serialisation is the API layer's business; these records are the contract.
 */
public final class GatewayIndex {

    private GatewayIndex() {
    }

    /**
     * V1: {@code { skills: [{ name, description, files }] }}.
     *
     * <p>The client updates itself by fetching every file and hashing them. That is why {@code
     * files} lists paths rather than carrying a digest: there is nowhere in this shape to put one.
     *
     * @param files paths relative to the skill root, not to the index — the client composes
     *              {@code <index-base>/<name>/<file>} from them
     */
    public record V1(List<Entry> skills) {
        public V1 {
            skills = List.copyOf(skills);
        }

        public record Entry(String name, String description, List<String> files) {
            public Entry {
                files = List.copyOf(files);
            }
        }
    }

    /**
     * V2: {@code { $schema, skills: [{ name, type, description, url, digest }] }}.
     *
     * <p>{@code digest} is a {@linkplain
     * com.skillmasterai.modules.gateway.internal.WellKnownDigest content hash}, not a version
     * digest — see that class for why confusing the two is a silent, permanent re-download.
     *
     * @param schema the draft schema URL, or null to omit the field. Nullable because the exact URL
     *               is <strong>unverifiable from here</strong>: §1.5 records that it lives under
     *               {@code agentskills.io} as the "v0.2.0 draft" and that it does not currently
     *               resolve, so any value written here would be invented rather than reproduced.
     *               A deployment that knows it sets it; until then the field is absent rather than
     *               wrong. P0b's CLI check is what settles it.
     * @param url    where to fetch the skill; absolute, because a client installing from this index
     *               is not necessarily talking to the host that served it
     */
    public record V2(String schema, List<Entry> skills) {
        public V2 {
            skills = List.copyOf(skills);
        }

        public record Entry(String name, String type, String description, String url, String digest) {
        }
    }

    /** The only {@code type} used for a single-file skill; {@code archive} would mean a tarball. */
    public static final String TYPE_SKILL_MD = "skill-md";
}
