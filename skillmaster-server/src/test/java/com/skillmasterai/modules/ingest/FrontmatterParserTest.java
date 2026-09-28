package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Each of the first four cases is a defect that the archived baseline actually had (known-issues
 * M5), reproduced there and pinned here as working.
 */
class FrontmatterParserTest {

    @Test
    void readsFlatScalars() {
        Map<String, Object> fields = parse("""
                ---
                name: pdf-tools
                title: PDF tools
                description: works with PDFs
                ---
                # body
                """);

        assertThat(fields)
                .containsEntry("name", "pdf-tools")
                .containsEntry("title", "PDF tools")
                .containsEntry("description", "works with PDFs");
    }

    @Test
    void toleratesAByteOrderMark() {
        // The baseline's `lines[0].strip() != "---"` guard did not remove U+FEFF, so a BOM made it
        // conclude there was no frontmatter and carry on with an empty one — silently.
        Map<String, Object> fields = parse("﻿---\nname: pdf-tools\ndescription: d\n---\n");

        assertThat(fields).containsEntry("name", "pdf-tools");
    }

    @Test
    void handlesInlineComments() {
        // Baseline: the comment became part of the value, so the name no longer matched anything.
        assertThat(parse("---\nname: pdf-tools  # the router\ndescription: d\n---\n"))
                .containsEntry("name", "pdf-tools");
    }

    @Test
    void handlesBlockScalars() {
        // Baseline: `|` was returned as the literal string "|" and the block was discarded, so a
        // multi-line description lost its entire text.
        Map<String, Object> fields = parse("""
                ---
                name: pdf-tools
                description: |
                  First line.
                  Second line.
                ---
                """);

        assertThat(String.valueOf(fields.get("description")))
                .contains("First line.")
                .contains("Second line.");
    }

    @Test
    void handlesNestedMappings() {
        // Baseline: `metadata:` with an indented block became the empty string, dropping Feishu's
        // metadata.requires.bins entirely.
        Map<String, Object> fields = parse("""
                ---
                name: pdf-tools
                description: d
                metadata:
                  requires:
                    bins: [pdftk]
                ---
                """);

        assertThat(fields.get("metadata")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) fields.get("metadata");
        assertThat(metadata).containsKey("requires");
    }

    @Test
    void keepsUnknownFields() {
        // §3.3 point 4: unknown fields are passed through verbatim, not dropped.
        Map<String, Object> fields = parse("""
                ---
                name: pdf-tools
                description: d
                something-unheard-of: kept
                ---
                """);

        assertThat(fields).containsEntry("something-unheard-of", "kept");
    }

    @Test
    void handlesCrlfLineEndings() {
        assertThat(parse("---\r\nname: pdf-tools\r\ndescription: d\r\n---\r\n"))
                .containsEntry("name", "pdf-tools");
    }

    @Test
    void rejectsWhatItCannotParseRatherThanGuessing() {
        // §3.3: a parse failure must not degrade silently. Each of these used to produce a
        // partial or wrong map, and the skill would then be published looking complete.
        assertThatThrownBy(() -> parse("# no frontmatter at all\n"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("must open with");
        assertThatThrownBy(() -> parse("---\nname: pdf-tools\n"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("never closed");
        assertThatThrownBy(() -> parse("---\n---\n"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("empty frontmatter");
        assertThatThrownBy(() -> parse("---\n- just\n- a list\n---\n"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("must be a YAML mapping");
        assertThatThrownBy(() -> parse("---\nname: [unclosed\n---\n"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> parse("---\nname: a\nname: b\n---\n"))
                .as("two values are equally plausible readings, so choosing one is a guess")
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void rejectsSkillMdThatIsNotUtf8() {
        assertThatThrownBy(() -> FrontmatterParser.parse(new byte[] {(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("not valid UTF-8");
    }

    private static Map<String, Object> parse(String skillMd) {
        return FrontmatterParser.parse(skillMd.getBytes(StandardCharsets.UTF_8));
    }
}
