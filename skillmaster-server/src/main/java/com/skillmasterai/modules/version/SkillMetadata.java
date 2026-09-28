package com.skillmasterai.modules.version;

/**
 * The skill-level facts a publish carries, separate from the files.
 *
 * <p>{@code frontmatterJson} is the whole parsed frontmatter as JSON, not just the fields we
 * understand: §3.3 requires unknown fields to be stored and passed through, which is how the
 * baseline's habit of dropping them is avoided. It is supplied as a string rather than as a map so
 * that M7 needs no opinion about serialisation.
 *
 * <p>Note what is absent: {@code visibility}. Metadata changes do not travel with a publish — §4.3
 * gives them their own endpoint, and a republish silently resetting visibility would be a way to
 * make a private skill public by accident.
 */
public record SkillMetadata(String name, String title, String description, String frontmatterJson) {
}
