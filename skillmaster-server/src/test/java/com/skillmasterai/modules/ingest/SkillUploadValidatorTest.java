package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.support.Zips;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillUploadValidatorTest {

    private static final String VALID_SKILL_MD = """
            ---
            name: pdf-tools
            title: PDF tools
            description: Extract and merge PDFs
            ---
            # PDF tools
            """;

    private final SkillUploadValidator validator = new SkillUploadValidator(IngestLimits.STANDARD);

    @Test
    void acceptsATreeWrappedInOneDirectoryNamedAfterTheSkill() {
        // `zip -r x.zip pdf-tools/` is the natural way to make an archive, so it is accepted and
        // the wrapper is stripped. §1.3's MUST — directory name equals name — is checked here,
        // because the zip's directory structure is not stored anywhere afterwards.
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "pdf-tools/SKILL.md", VALID_SKILL_MD,
                "pdf-tools/references/checklist.md", "checklist")));

        assertThat(upload.name()).isEqualTo("pdf-tools");
        assertThat(upload.title()).isEqualTo("PDF tools");
        assertThat(upload.description()).isEqualTo("Extract and merge PDFs");
        assertThat(upload.files()).extracting(IngestedFile::relpath)
                .containsExactlyInAnyOrder("SKILL.md", "references/checklist.md");
    }

    @Test
    void acceptsATreeAtTheArchiveRoot() {
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "references/checklist.md", "checklist")));

        assertThat(upload.files()).extracting(IngestedFile::relpath)
                .containsExactlyInAnyOrder("SKILL.md", "references/checklist.md");
    }

    @Test
    void includesSkillMdAmongTheFiles() {
        // §1.3 requires the manifest to list SKILL.md alongside the attachments; it is not a
        // special case that lives outside the file set.
        SkillUpload upload = validator.validate(Zips.ofText(Map.of("SKILL.md", VALID_SKILL_MD)));

        assertThat(upload.files()).extracting(IngestedFile::relpath).containsExactly("SKILL.md");
        assertThat(upload.skillMd().bytes()).isEqualTo(VALID_SKILL_MD.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fallsBackToTheNameWhenThereIsNoTitle() {
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n")));

        assertThat(upload.title()).isEqualTo("pdf-tools");
    }

    @Test
    void ignoresDirectoryEntries() {
        SkillUpload upload = validator.validate(Zips.withDirectoryEntry("references",
                Map.of("SKILL.md", validSkillMd().getBytes(StandardCharsets.UTF_8))));

        assertThat(upload.files()).extracting(IngestedFile::relpath).containsExactly("SKILL.md");
    }

    @Test
    void rejectsADirectoryNameThatDisagreesWithTheFrontmatter() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "something-else/SKILL.md", VALID_SKILL_MD))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("something-else")
                .hasMessageContaining("pdf-tools");
    }

    @Test
    void rejectsASymbolicLink() {
        // A symlink entry holds its target as content. Publishing it would store bytes the author
        // never uploaded, and the digest would then describe a file they cannot see.
        assertThatThrownBy(() -> validator.validate(Zips.withSymlink("SKILL.md", "/etc/passwd")))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsPathsThatEscapeTheSkillRoot() {
        // Built with ofRawPaths: these three names are exactly the ones a normal archive writer
        // rewrites on the way out, so a helper that sanitises would leave nothing to reject.
        assertThatThrownBy(() -> validator.validate(raw(Map.of(
                "../evil.md", "x", "SKILL.md", validSkillMd()))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("escapes the skill root");

        assertThatThrownBy(() -> validator.validate(raw(Map.of(
                "/etc/passwd", "x", "SKILL.md", validSkillMd()))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("absolute path");

        // The fourth shape ZipReader guards against — a backslash — has no test here on purpose.
        // commons-compress normalises '\' to '/' on read, so a backslash name cannot reach the
        // validator through it; ZipsTest.aBackslashInTheBytesIsNormalisedOnTheWayBackOut pins that
        // behaviour. Asserting a rejection would mean asserting against a fixture the reader has
        // already rewritten.
    }

    private static byte[] raw(Map<String, String> entries) {
        Map<String, byte[]> asBytes = new LinkedHashMap<>();
        entries.forEach((path, text) -> asBytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return Zips.ofRawPaths(asBytes);
    }

    @Test
    void rejectsAnUploadWithNoSkillMd() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of("README.md", "hi"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsFrontmatterWithoutTheFieldsSearchDependsOn() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\ndescription: d\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("name");

        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\n---\n"))))
                .as("an empty description is a skill nobody can find")
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejectsANameThatCouldNotBeADirectory() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf tools\ndescription: d\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsAFieldOfTheWrongShapeRatherThanStringifyingIt() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription:\n  nested: value\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("must be text");
    }

    @Test
    void enforcesTheFileCountCeiling() {
        SkillUploadValidator tiny = new SkillUploadValidator(new IngestLimits(2, 1_000_000));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", validSkillMd());
        files.put("a.md", "a");
        files.put("b.md", "b");

        assertThatThrownBy(() -> tiny.validate(Zips.ofText(files)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("more than 2 files");
    }

    @Test
    void enforcesTheByteCeilingOnWhatIsActuallyDeliveredNotOnWhatIsDeclared() {
        // The declared sizes in a zip header can be lies; the reader counts bytes as it reads.
        SkillUploadValidator tiny = new SkillUploadValidator(new IngestLimits(512, 100));

        assertThatThrownBy(() -> tiny.validate(Zips.ofText(Map.of(
                "SKILL.md", validSkillMd(),
                "big.md", "x".repeat(500)))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("exceeds 100 bytes");
    }

    @Test
    void rejectsSomethingThatIsNotAZip() {
        assertThatThrownBy(() -> validator.validate("not a zip at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("not a readable zip");
    }

    @Test
    void rejectsAnEmptyArchive() {
        assertThatThrownBy(() -> validator.validate(Zips.of(Map.of())))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("no files");
    }

    private static String validSkillMd() {
        return VALID_SKILL_MD;
    }
}
